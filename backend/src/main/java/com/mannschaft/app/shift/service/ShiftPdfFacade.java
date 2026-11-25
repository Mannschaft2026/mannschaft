package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * シフト PDF の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W6a: 認可を {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を
 * <b>付けない</b>。流れは「{@link ShiftScheduleService#resolveScope} の readOnly な scope 解決 → 認可 →
 * {@link ShiftPdfService} の tx 本体（中でスケジュール・枠を読み直し、公開状態を再判定する）」。</p>
 *
 * <p>是正前は、越境の 404 隠蔽を {@code ShiftScheduleService#getSchedule} の認可に頼っていた
 *（{@code ShiftPdfService} 側の認可は 403）。tx 本体から認可を抜くと PDF が新しい存在オラクルになるため、
 * 同じ判定順（越境の 404 → SUPPORTER の 403 → 未公開の 404。SUPPORTER に応答差で公開状態を読ませない）をここへ移した。</p>
 *
 * <h3>認可表（是正前の契約から変えていない）</h3>
 * <pre>
 * 主体                          応答
 * 部外者(越境)                  404 SHIFT_001（不在 ID と同一）
 * 同チーム SUPPORTER            403 COMMON_002（未公開でも同じ 403）
 * 同チーム一般メンバー          200（公開済みのみ。COLLECTING・ADJUSTING・未公開は 404 SHIFT_001）
 * チーム ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN（在籍あり）  200（未公開も可）
 * MANAGE_SHIFTS 無しの DEPUTY_ADMIN  閲覧切替 OFF=一般メンバーと同じ／ON=管理者の形（未公開も可。CMP-261125-2114）
 * user_roles のみの ADMIN       403 COMMON_002（是正前から在籍を要求。許可は広げない）
 * user_roles のみの DEPUTY_ADMIN（MANAGE_SHIFTS 無し・切替 OFF） 403 COMMON_002（所属者。越境の 404 にはしない）
 * SYSTEM_ADMIN(チームロール無し) 部外者と同じ 404 SHIFT_001（CMP-261125-2114: SYSTEM_ADMIN を特別扱いしない）
 * 対象不在・削除済み            404 SHIFT_001
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ShiftPdfFacade {

    private static final String TEAM = "TEAM";

    private final ShiftScheduleService scheduleService;
    private final ShiftPdfService pdfService;
    private final AccessControlService accessControlService;
    private final ShiftManageAccessGate manageGate;

    /**
     * チーム全体表 PDF を生成する。
     *
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID
     * @return PDF の byte[]
     */
    public byte[] generateTeamPdf(Long scheduleId, Long requesterId) {
        boolean privileged = authorize(scheduleId, requesterId);
        return pdfService.generateTeamPdf(scheduleId, requesterId, privileged);
    }

    /**
     * 個人タイムライン PDF を生成する。
     *
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID（個人フィルタ用）
     * @return PDF の byte[]
     */
    public byte[] generatePersonalPdf(Long scheduleId, Long requesterId) {
        boolean privileged = authorize(scheduleId, requesterId);
        return pdfService.generatePersonalPdf(scheduleId, requesterId, privileged);
    }

    /**
     * PDF 発行の認可。メンバー（MEMBER 以上、SUPPORTER は不可）のみ。チームロールを持たない SYSTEM_ADMIN は不可。
     *
     * @return 管理者の形（ADMIN・MANAGE_SHIFTS 付き DEPUTY_ADMIN、閲覧切替 ON の DEPUTY_ADMIN）なら true（未公開でも発行できる）
     */
    private boolean authorize(Long scheduleId, Long requesterId) {
        ShiftScheduleScope scope = scheduleService.resolveScope(scheduleId);
        // 管理者の形（閲覧扱い）。SYSTEM_ADMIN を特別扱いしない（CMP-261125-2114）。
        boolean privileged = manageGate.canView(requesterId, scope.teamId());
        if (!privileged) {
            // 越境（所属していない）は不在と同じ 404。SUPPORTER は公開状態に依らず一律 403、
            // 未公開の 404 はその後（404/403 の差で公開状態を読ませない。CMP-260826-2127 D-2127-1）。
            // 所属していない（越境）は 404、所属しているが在籍していない（user_roles のみの副管理者等）は 403。
            manageGate.requireMemberOrConceal(requesterId, scope.teamId(), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            if (accessControlService.isSupporter(requesterId, scope.teamId(), TEAM)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            if (scope.isHidden()) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
        } else if (!accessControlService.isMember(requesterId, scope.teamId(), TEAM)) {
            // user_roles のみの管理者: PDF は是正前から在籍を要求して 403（許可を広げない）。
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        // SUPPORTER は閲覧者ロールで、PDF のような運用情報へは出せない（IDOR 防止も兼ねる）。
        if (accessControlService.isSupporter(requesterId, scope.teamId(), TEAM)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return privileged;
    }
}
