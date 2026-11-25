package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.dto.CreateHourlyRateRequest;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * シフト時給サービス。メンバーの時給設定・履歴管理を担当する。
 *
 * <p><b>認可（CMP-261125-2114）:</b> 時給は金銭情報のため、<b>参照</b>の認可は tx の外の
 * {@link ShiftHourlyRateFacade}（本人・ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN・時給閲覧切替 ON の DEPUTY_ADMIN）が済ませ、
 * 本クラスの参照メソッドは認可なしの tx 本体（時給閲覧切替が team ドメインの設定のため、tx の中で読まない。D-3T）。
 * <b>登録・訂正</b>は {@link ShiftHourlyRateAuthorizer#checkHourlyRateWriteAccess}（ADMIN と MANAGE_SHIFTS を持つ
 * DEPUTY_ADMIN だけ。SYSTEM_ADMIN は特別扱いしない）で強制する。削除は {@link ShiftHourlyRateDeletionFacade}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftHourlyRateService {

    private final ShiftHourlyRateRepository hourlyRateRepository;
    private final ShiftMapper shiftMapper;
    private final ShiftHourlyRateAuthorizer hourlyRateAuthorizer;

    /**
     * ユーザーの時給履歴を取得する（認可なしの tx 本体）。
     *
     * <p>認可（本人・ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN・時給閲覧切替 ON の DEPUTY_ADMIN）は
     * {@link ShiftHourlyRateFacade} が tx の外で済ませてから呼ぶ。Controller は本メソッドを直接呼ばないこと。</p>
     *
     * @param userId 対象ユーザーID
     * @param teamId チームID
     * @return 時給履歴一覧
     */
    public List<HourlyRateResponse> listHourlyRates(Long userId, Long teamId) {
        List<ShiftHourlyRateEntity> entities = hourlyRateRepository
                .findByUserIdAndTeamIdOrderByEffectiveFromDesc(userId, teamId);
        return shiftMapper.toHourlyRateResponseList(entities);
    }

    /**
     * 特定日時点の有効時給を取得する（認可なしの tx 本体。認可は {@link ShiftHourlyRateFacade}）。
     *
     * @param userId 対象ユーザーID
     * @param teamId チームID
     * @param date   基準日
     * @return 有効な時給（存在しない場合はnull）
     */
    public HourlyRateResponse getEffectiveRate(Long userId, Long teamId, LocalDate date) {
        return hourlyRateRepository.findEffectiveRate(userId, teamId, date)
                .map(shiftMapper::toHourlyRateResponse)
                .orElse(null);
    }

    /**
     * チーム全員ぶんの「基準日時点で有効な時給」を 1 クエリで取得する（CMP-260912-1525。認可なしの tx 本体）。
     *
     * <h2>なぜ要るのか</h2>
     * <p>時給設定画面は全メンバーの現在時給を並べる。1 人ずつ
     * {@link #getEffectiveRate} を引くと、人数ぶんの HTTP 往復とクエリが出る。
     * 本メソッドは走査を 1 クエリに固定する（総処理量は人数 N に比例）。</p>
     *
     * <h2>認可（AC-5）</h2>
     * <p>返す内容に他メンバーの時給が必ず含まれるため、認可は {@link ShiftHourlyRateFacade} が tx の外で
     * 「ADMIN／MANAGE_SHIFTS を持つ DEPUTY_ADMIN（時給閲覧切替 ON の DEPUTY_ADMIN を含む）だけ」に絞る
     * （CMP-261125-2114。SYSTEM_ADMIN は特別扱いしない）。</p>
     *
     * <h2>対象の絞り込み（Codex 検分 P1）</h2>
     * <p>ロールの検査だけでは「誰の時給を返すか」が決まらない。teamId だけで引くと、時給を設定されたあとに脱退した
     * 元メンバーの金銭情報まで返る。在籍中メンバーの ID（呼び出し側が tx の外で 1 クエリで解決）に限定して引く。</p>
     *
     * @param teamId          チームID
     * @param date            基準日
     * @param activeMemberIds 在籍中メンバーの ID（認可後に呼び出し側が解決して渡す）
     * @return ユーザーごとの有効時給（基準日時点で時給が無いユーザーは含まれない）
     */
    public List<HourlyRateResponse> listEffectiveRatesForTeam(Long teamId, LocalDate date,
                                                              List<Long> activeMemberIds) {
        if (activeMemberIds.isEmpty()) {
            // 空リストを IN 句へ渡すと JPQL が不正になる。在籍者が居なければ返す時給も無い。
            return List.of();
        }
        List<ShiftHourlyRateEntity> entities =
                hourlyRateRepository.findEffectiveRatesByTeam(teamId, date, activeMemberIds);
        return shiftMapper.toHourlyRateResponseList(entities);
    }

    /**
     * 時給を設定する。
     *
     * <p><b>同じ適用開始日の再登録は「訂正」として更新する（CMP-260910-1555）</b>:
     * {@code shift_hourly_rates} には {@code uq_shr_user_team_from (user_id, team_id, effective_from)}
     * の一意制約がある。是正前は常に INSERT していたため、<b>当日登録した時給の打ち間違いに
     * 気づいて同じ日付で訂正しようとすると、必ず一意制約違反で失敗</b>していた
     * （現在の時給の適用開始日が今日であるケースは、登録直後には常に成立する）。</p>
     *
     * <p>「この人のこの日からの時給はいくらか」という指定は (user, team, effective_from) に対して
     * <b>冪等</b>であり、同じ日付への再指定は新しい履歴ではなく訂正である。よって既存行があれば
     * 金額を更新する。別の日付での登録は従来どおり追加であり、<b>過去の履歴は消えない</b>。</p>
     *
     * <p>判定は<b>アプリ層で分岐せず、一意制約に衝突解決させる単一文</b>で行う
     * （{@code ON DUPLICATE KEY UPDATE}）。「既存を探して無ければ INSERT」だと読みと書きの間に
     * 窓が開き、同じキーへの初回リクエストが並行したときに両方が「既存なし」を見て
     * 両方 INSERT へ進み、片方が制約違反で落ちる。二重送信やリトライで普通に起きるため、
     * アプリ層の分岐では冪等性を保証できない。</p>
     *
     * @param teamId        チームID
     * @param req           設定リクエスト
     * @param currentUserId 操作ユーザーID（認可判定に使用）
     * @return 設定された時給
     */
    @Transactional
    public HourlyRateResponse createHourlyRate(Long teamId, CreateHourlyRateRequest req, Long currentUserId) {
        hourlyRateAuthorizer.checkHourlyRateWriteAccess(currentUserId, req.getUserId(), teamId);

        // 一意制約 uq_shr_user_team_from に衝突解決させる単一文（詳細は upsertHourlyRate の Javadoc）。
        // SELECT してから INSERT/UPDATE を分岐すると読みと書きの間に窓が開き、
        // 同じキーへの初回リクエストが並行したときに片方が制約違反で落ちる。
        hourlyRateRepository.upsertHourlyRate(
                req.getUserId(), teamId, req.getHourlyRate(), req.getEffectiveFrom());

        ShiftHourlyRateEntity saved = hourlyRateRepository
                .findByUserIdAndTeamIdAndEffectiveFrom(req.getUserId(), teamId, req.getEffectiveFrom())
                .orElseThrow(() -> new IllegalStateException(
                        "時給の upsert 直後に対象行を読み戻せない: userId=" + req.getUserId()
                                + ", teamId=" + teamId + ", effectiveFrom=" + req.getEffectiveFrom()));

        log.info("時給設定: id={}, userId={}, teamId={}, rate={}, effectiveFrom={}",
                saved.getId(), req.getUserId(), teamId, req.getHourlyRate(), req.getEffectiveFrom());
        return shiftMapper.toHourlyRateResponse(saved);
    }
}
