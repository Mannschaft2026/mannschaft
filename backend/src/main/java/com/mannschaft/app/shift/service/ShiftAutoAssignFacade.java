package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.AssignmentRunResponse;
import com.mannschaft.app.shift.dto.AutoAssignRequest;
import com.mannschaft.app.shift.dto.ConfirmAutoAssignRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * シフト自動割当の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W2: 認可を {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を
 * <b>付けない</b>（クラスにもメソッドにも）。流れは「{@link ShiftAutoAssignService} の readOnly な scope 解決
 * （ロックなし・自ドメインのみ）→ 認可 → {@link ShiftAutoAssignService} の tx（中でスケジュール行を
 * {@code FOR UPDATE} して読み直す）」。これにより {@code FOR UPDATE} は認可の<b>後</b>になり、部外者の
 * リクエストが他チームの行ロックを取ることがない（K6）。Controller は本クラスだけを呼ぶ。
 * 名前を {@code *AccessService} / {@code *AccessGate} にしないのは、呼んだだけで認可シグナル扱いになり
 * 本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（許可主体は維持、親不在を入力runの不在へ統一・CMP-260903-0657）</h3>
 * <pre>
 * 主体＼EP                    実行/確定/破棄(schedule)   履歴一覧(schedule)  詳細・目視確認(runId)
 * 部外者(非メンバー)          404 SHIFT_001/SHIFT_024*   404 SHIFT_001       404 SHIFT_024
 * 同チーム一般メンバー        403 COMMON_002             403 COMMON_002      404 SHIFT_024(同チームでも隠す)
 * ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN  200/201/204(状態次第)  200  200(状態次第)
 * MANAGE_SHIFTS 無しの DEPUTY_ADMIN  403 COMMON_002  403 COMMON_002  404 SHIFT_024（一般メンバーと同じ。閲覧切替でも広がらない）
 * SYSTEM_ADMIN(チームロール無し)  部外者と同じ（CMP-261125-2114: SYSTEM_ADMIN を特別扱いしない）
 * user_roles のみの ADMIN     200/201/204(状態次第)      200                 200(状態次第)
 * 他チームの ADMIN            404 SHIFT_001/SHIFT_024*   404 SHIFT_001       404 SHIFT_024
 * 対象不在                    404 SHIFT_001/SHIFT_024*   404 SHIFT_001       404 SHIFT_024
 * 親だけ不在(スケジュール)    404 SHIFT_001/SHIFT_024*   404 SHIFT_001       404 SHIFT_024
 * *実行(schedule): 越境・不在とも SHIFT_001。確定・破棄(run): run 不在・パスのスケジュールと不一致・越境は
 *  SHIFT_024、スケジュールだけ不在の場合も同じ SHIFT_024。
 * DB 変更: 拒否・不在はすべて変更なし（run 行・割当行・スロット行とも不変）。
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ShiftAutoAssignFacade {

    private final ShiftAutoAssignService autoAssignService;
    private final ShiftManageAccessGate manageGate;

    /**
     * 自動割当を実行する（スケジュール実体から解決したチームの ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ）。
     *
     * @param scheduleId  スケジュール ID
     * @param request     自動割当リクエスト
     * @param triggeredBy 実行者ユーザー ID
     * @return 実行ログレスポンス
     */
    public AssignmentRunResponse runAutoAssign(Long scheduleId, AutoAssignRequest request, Long triggeredBy) {
        Long teamId = autoAssignService.resolveScheduleTeamId(scheduleId);
        // 越境は兄弟（ShiftScheduleService）と同じく不在（SHIFT_SCHEDULE_NOT_FOUND）と同一応答へ畳む。
        manageGate.requireManageOrConceal(triggeredBy, teamId, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        return autoAssignService.runAutoAssign(scheduleId, request, triggeredBy);
    }

    /**
     * 自動割当提案を確定する（run 実体由来のチームの ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ）。
     *
     * @param scheduleId スケジュール ID
     * @param request    確定リクエスト
     * @param userId     操作者ユーザー ID
     */
    public void confirmAutoAssign(Long scheduleId, ConfirmAutoAssignRequest request, Long userId) {
        Long teamId = autoAssignService.resolveRunTeamId(request.runId(), scheduleId);
        manageGate.requireManageOrConceal(userId, teamId, ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        autoAssignService.confirmAutoAssign(scheduleId, request);
    }

    /**
     * 自動割当提案を破棄する（run 実体由来のチームの ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ）。
     *
     * @param scheduleId スケジュール ID
     * @param runId      実行ログ ID
     * @param userId     操作者ユーザー ID
     */
    public void revokeAutoAssign(Long scheduleId, Long runId, Long userId) {
        Long teamId = autoAssignService.resolveRunTeamId(runId, scheduleId);
        manageGate.requireManageOrConceal(userId, teamId, ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        autoAssignService.revokeAutoAssign(scheduleId, runId);
    }

    /**
     * 自動割当実行履歴一覧を取得する（管理者専用の運用情報。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN 粒度）。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @return 実行ログ一覧
     */
    public List<AssignmentRunResponse> getAssignmentRuns(Long scheduleId, Long userId) {
        Long teamId = autoAssignService.resolveScheduleTeamId(scheduleId);
        manageGate.requireManageOrConceal(userId, teamId, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        return autoAssignService.getAssignmentRuns(scheduleId);
    }

    /**
     * 自動割当実行ログ詳細を取得する（run 実体由来のチームの ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ）。
     *
     * @param runId  実行ログ ID
     * @param userId 操作者ユーザー ID
     * @return 実行ログ詳細
     */
    public AssignmentRunResponse getAssignmentRunDetail(Long runId, Long userId) {
        requireRunAdminConcealed(runId, userId);
        return autoAssignService.getAssignmentRunDetail(runId);
    }

    /**
     * 目視確認を完了させる（確定と同一粒度。run 実体由来のチームの ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     *
     * @param runId  実行ログ ID
     * @param note   確認備考
     * @param userId 確認者ユーザー ID
     */
    public void confirmVisualReview(Long runId, String note, Long userId) {
        requireRunAdminConcealed(runId, userId);
        autoAssignService.confirmVisualReview(runId, note, userId);
    }

    /**
     * run 実体由来の管理者認可（<b>存在秘匿版</b>）。権限が無い場合も 403 ではなく
     * {@code ASSIGNMENT_RUN_NOT_FOUND}（404）を返す。
     *
     * <p>パスにスコープを持たない {@code /assignment-runs/{runId}} 系の EP 専用。本 EP は
     * 「同チームの管理操作の権限が無い人も不在と同じ 404」が仕様なので、権限不足を 403 で返す判定は使わず、
     * {@code canManage}（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）の真偽だけで 404 に統一する。</p>
     */
    private void requireRunAdminConcealed(Long runId, Long userId) {
        Long teamId = autoAssignService.resolveRunTeamId(runId, null);
        if (!manageGate.canManage(userId, teamId)) {
            throw new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        }
    }
}
