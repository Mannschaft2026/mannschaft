package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.dto.MemberWorkConstraintRequest;
import com.mannschaft.app.shift.dto.MemberWorkConstraintResponse;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.repository.MemberWorkConstraintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * メンバー勤務制約サービス（F03.5 v2 新規）。
 *
 * <p>チームデフォルト（{@code userId IS NULL}）と個別オーバーライドの両方を
 * 同一テーブル上で管理する。解決順序は「個別レコード → チームデフォルト → 制約なし」。</p>
 *
 * <p>権限モデル（CMP-261125-2114。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけが管理者。
 * DEPUTY_ADMIN というだけでは許可しない。SYSTEM_ADMIN も特別扱いしない）:
 * <ul>
 *   <li>作成・更新・削除: ADMIN または MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ
 *       （個別制約の upsert だけは認可と対象の在籍検査を {@link MemberWorkConstraintFacade}（tx の外）が行い、
 *       本クラスの {@link #upsertConstraint} は認可しない tx 本体）</li>
 *   <li>一覧閲覧（全メンバー）: ADMIN または MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ</li>
 *   <li>個別閲覧: 本人 または ADMIN／MANAGE_SHIFTS を持つ DEPUTY_ADMIN（MEMBER は自分の制約のみ閲覧可）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberWorkConstraintService {

    private static final String SCOPE_TEAM = "TEAM";

    private final MemberWorkConstraintRepository constraintRepository;
    private final ShiftMapper shiftMapper;
    private final AccessControlService accessControlService;
    private final ShiftManageAccessGate manageGate;

    // ========================================
    // 取得系
    // ========================================

    /**
     * チーム内の全勤務制約（デフォルト + 個別）を取得する。認可はしない（ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN の判定は MemberWorkConstraintFacade）。
     */
    public List<MemberWorkConstraintResponse> listConstraintsByTeam(Long teamId, Long currentUserId) {
        List<MemberWorkConstraintEntity> entities = constraintRepository.findByTeamId(teamId);
        return shiftMapper.toWorkConstraintResponseList(entities);
    }

    /**
     * 個別勤務制約を取得する。
     *
     * <p>解決順序: 個別レコード（存在すれば） → チームデフォルト → 制約なし（404）。
     * 非管理者（MEMBER）は自分の制約のみ閲覧可能。他メンバーの制約を要求した場合は 403。</p>
     *
     * @param teamId        チーム ID
     * @param userId        対象ユーザー ID
     * @param currentUserId 呼び出し元ユーザー ID
     * @return 勤務制約レスポンス
     * @throws BusinessException 制約が存在しない場合
     *                           ({@link ShiftErrorCode#WORK_CONSTRAINT_NOT_FOUND})、
     *                           権限不足の場合
     *                           ({@link ShiftErrorCode#WORK_CONSTRAINT_FORBIDDEN})
     */
    public MemberWorkConstraintResponse getConstraint(Long teamId, Long userId, Long currentUserId) {
        // 権限チェック: 本人 or ADMIN／MANAGE_SHIFTS を持つ DEPUTY_ADMIN
        if (!userId.equals(currentUserId) && !manageGate.canManage(currentUserId, teamId)) {
            throw new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        }
        // 閲覧者自身はチームメンバーである必要がある
        accessControlService.checkMembership(currentUserId, teamId, SCOPE_TEAM);

        MemberWorkConstraintEntity resolved = resolveConstraint(teamId, userId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));
        return shiftMapper.toWorkConstraintResponse(resolved);
    }

    /**
     * チームデフォルトを取得する。
     *
     * @param teamId        チーム ID
     * @param currentUserId 呼び出し元ユーザー ID（チームメンバーである必要がある）
     * @return チームデフォルトの制約
     * @throws BusinessException デフォルトが未設定の場合
     */
    public MemberWorkConstraintResponse getTeamDefault(Long teamId, Long currentUserId) {
        // チームデフォルトは透明性のため全メンバーが閲覧可能
        accessControlService.checkMembership(currentUserId, teamId, SCOPE_TEAM);

        MemberWorkConstraintEntity entity = constraintRepository.findTeamDefault(teamId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));
        return shiftMapper.toWorkConstraintResponse(entity);
    }

    /**
     * 個別 → デフォルトの解決順序で制約レコードを取得する（Service 内部 + Phase 2 自動割当用）。
     *
     * <p>package-private なヘルパ。Phase 2 の自動割当サービスから参照される想定。</p>
     *
     * @param teamId チーム ID
     * @param userId ユーザー ID
     * @return 解決された制約（個別 → デフォルト → {@link Optional#empty()}）
     */
    Optional<MemberWorkConstraintEntity> resolveConstraint(Long teamId, Long userId) {
        Optional<MemberWorkConstraintEntity> individual = constraintRepository
                .findByUserIdAndTeamId(userId, teamId);
        if (individual.isPresent()) {
            return individual;
        }
        return constraintRepository.findTeamDefault(teamId);
    }

    // ========================================
    // 更新系
    // ========================================

    /**
     * 個別勤務制約を upsert する（<b>認可しない tx 本体</b>）。
     *
     * <p>認可（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）と、対象ユーザーがそのチームの在籍者であることの検査は
     * {@link MemberWorkConstraintFacade}（tx の外）が済ませてから呼ぶ（tx 内から membership・role の Repository へ
     * 到達させない。D-3T）。Controller は本メソッドを直接呼ばないこと。</p>
     *
     * <p>全項目 NULL のリクエストは拒否（{@link ShiftErrorCode#WORK_CONSTRAINT_ALL_NULL}）。
     * 既存レコードが存在すれば更新、なければ新規作成。</p>
     *
     * @param currentUserId 認可済みの操作者（監査ログ用）
     */
    @Transactional
    public MemberWorkConstraintResponse upsertConstraint(
            Long teamId, Long userId, MemberWorkConstraintRequest req, Long currentUserId) {
        validateNotAllNull(req);

        MemberWorkConstraintEntity entity = constraintRepository
                .findByUserIdAndTeamId(userId, teamId)
                .map(existing -> {
                    existing.updateConstraints(
                            req.getMaxMonthlyHours(),
                            req.getMaxMonthlyDays(),
                            req.getMaxConsecutiveDays(),
                            req.getMaxNightShiftsPerMonth(),
                            req.getMinRestHoursBetweenShifts(),
                            req.getNote());
                    return existing;
                })
                .orElseGet(() -> MemberWorkConstraintEntity.builder()
                        .teamId(teamId)
                        .userId(userId)
                        .maxMonthlyHours(req.getMaxMonthlyHours())
                        .maxMonthlyDays(req.getMaxMonthlyDays())
                        .maxConsecutiveDays(req.getMaxConsecutiveDays())
                        .maxNightShiftsPerMonth(req.getMaxNightShiftsPerMonth())
                        .minRestHoursBetweenShifts(req.getMinRestHoursBetweenShifts())
                        .note(req.getNote())
                        .build());

        entity = constraintRepository.save(entity);
        log.info("メンバー勤務制約 upsert: teamId={}, userId={}, id={}, by={}",
                teamId, userId, entity.getId(), currentUserId);
        return shiftMapper.toWorkConstraintResponse(entity);
    }

    /**
     * チームデフォルトを upsert する。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ。
     *
     * <p>MySQL の UNIQUE KEY は {@code NULL != NULL} を許容するため、Service 層で
     * 「1チーム1デフォルト」を保証する（{@link MemberWorkConstraintRepository#findTeamDefault}）。</p>
     */
    @Transactional
    public MemberWorkConstraintResponse upsertTeamDefault(
            Long teamId, MemberWorkConstraintRequest req, Long currentUserId) {
        validateNotAllNull(req);

        MemberWorkConstraintEntity entity = constraintRepository.findTeamDefault(teamId)
                .map(existing -> {
                    existing.updateConstraints(
                            req.getMaxMonthlyHours(),
                            req.getMaxMonthlyDays(),
                            req.getMaxConsecutiveDays(),
                            req.getMaxNightShiftsPerMonth(),
                            req.getMinRestHoursBetweenShifts(),
                            req.getNote());
                    return existing;
                })
                .orElseGet(() -> MemberWorkConstraintEntity.builder()
                        .teamId(teamId)
                        .userId(null)
                        .maxMonthlyHours(req.getMaxMonthlyHours())
                        .maxMonthlyDays(req.getMaxMonthlyDays())
                        .maxConsecutiveDays(req.getMaxConsecutiveDays())
                        .maxNightShiftsPerMonth(req.getMaxNightShiftsPerMonth())
                        .minRestHoursBetweenShifts(req.getMinRestHoursBetweenShifts())
                        .note(req.getNote())
                        .build());

        entity = constraintRepository.save(entity);
        log.info("チームデフォルト勤務制約 upsert: teamId={}, id={}", teamId, entity.getId());
        return shiftMapper.toWorkConstraintResponse(entity);
    }

    // ========================================
    // 削除系
    // ========================================

    /**
     * 個別勤務制約を削除する。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ。
     *
     * @throws BusinessException 制約が存在しない場合
     *                           ({@link ShiftErrorCode#WORK_CONSTRAINT_NOT_FOUND})
     */
    @Transactional
    public void deleteConstraint(Long teamId, Long userId, Long currentUserId) {
        MemberWorkConstraintEntity entity = constraintRepository
                .findByUserIdAndTeamId(userId, teamId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));
        constraintRepository.delete(entity);
        log.info("メンバー勤務制約削除: teamId={}, userId={}, id={}", teamId, userId, entity.getId());
    }

    /**
     * チームデフォルトを削除する。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ。
     */
    @Transactional
    public void deleteTeamDefault(Long teamId, Long currentUserId) {
        MemberWorkConstraintEntity entity = constraintRepository.findTeamDefault(teamId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));
        constraintRepository.delete(entity);
        log.info("チームデフォルト勤務制約削除: teamId={}, id={}", teamId, entity.getId());
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * 全項目 NULL のリクエストを拒否する（DTO レベルの全NULL拒否）。
     */
    private void validateNotAllNull(MemberWorkConstraintRequest req) {
        if (req.isAllConstraintsNull()) {
            throw new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_ALL_NULL);
        }
    }
}
