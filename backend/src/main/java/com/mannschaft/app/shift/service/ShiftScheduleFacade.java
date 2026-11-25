package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * シフトスケジュールの <b>認可ファサード</b>（トランザクションの外）。手動リマインドもここに置く。
 *
 * <p>CMP-260923-0954 W6a: 認可（{@code AccessControlService} / {@code ScopeConcealingAccessGate}）を
 * {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは「{@link ShiftScheduleService#resolveScope} の readOnly な scope 解決（自ドメインのみ・FOR UPDATE なし）→
 * 認可 → {@link ShiftScheduleService} / {@link ShiftPreferenceReminderBatchService} の tx 本体（中で読み直す）」。
 * Controller は本クラスだけを呼ぶ。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。
 * 管理操作の判定は {@link ShiftManageAccessGate}（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけ）。</p>
 *
 * <h3>認可表（未公開の詳細・サマリは後述の 404 秘匿を適用）</h3>
 * <pre>
 * 主体＼EP                  一覧(teamId)   詳細(id)         作成(teamId)    更新/削除/遷移/サマリ/複製/remind(id)
 * 部外者(越境)              403 COMMON_002 404 SHIFT_001    403 COMMON_002  404 SHIFT_001（remind も。是正前は 403）
 * 同チーム SUPPORTER        403 COMMON_002 403 COMMON_002   403 COMMON_002  403 COMMON_002
 * 同チーム一般メンバー      200            200              403 COMMON_002  403 COMMON_002
 * ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN  200(全件)  200  201             200/204/201
 * MANAGE_SHIFTS 無しの DEPUTY_ADMIN  切替OFF=一般メンバーと同じ／閲覧切替ON=閲覧だけ管理者の形（一覧・詳細・サマリ）。
 *                           作成・更新・削除・遷移・複製・remind は切替に関わらず 403 COMMON_002
 * user_roles のみの ADMIN   403 COMMON_002 200              201             200/204/201（一覧は是正前から在籍を要求。広げない）
 * user_roles のみの DEPUTY_ADMIN（MANAGE_SHIFTS 無し・切替 OFF） 一覧 403／詳細 403 COMMON_002（所属者。越境の 404 にはしない）／
 *                           作成・更新等も 403 COMMON_002（MANAGE_SHIFTS 無し）。在籍していないので一般メンバーとしても見られない
 * SYSTEM_ADMIN(チームロール無し) 一覧 403 COMMON_002／他は部外者と同じ（CMP-261125-2114: SYSTEM_ADMIN を特別扱いしない）
 * 対象不在・親削除済み      (n/a)          404 SHIFT_001    (n/a)           404 SHIFT_001
 * </pre>
 * <p>未公開（DRAFT・ARCHIVED で publishedAt なし）の詳細・サマリは、一般メンバーには 404 SHIFT_001 へ寄せる
 * （存在オラクル対策）。SUPPORTER は公開状態に依らず既存の 403 を維持する。
 * 一覧・作成は {@code ?teamId=} 直接指定なので越境も不在も 403 のまま（隠さない）。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftScheduleFacade {

    private static final String TEAM = "TEAM";

    private final ShiftScheduleService scheduleService;
    private final ShiftPreferenceReminderBatchService reminderService;
    private final ShiftManageAccessGate manageGate;
    private final AccessControlService accessControlService;
    /** CMP-261115-1821: 公開の予算連動判定の値を公開 TX の外で取得する口（実装は shiftbudget）。 */
    private final ShiftPublicationBudgetContextPort publicationBudgetContextPort;

    /**
     * チームのシフトスケジュール一覧を取得する（期間指定は {@code from}・{@code to} が両方あるとき）。
     *
     * <p>当該チームの非 SUPPORTER メンバーのみ。それ以外（チームロールを持たない SYSTEM_ADMIN を含む）は
     * 403（{@code COMMON_002}）。管理者の形（ADMIN・MANAGE_SHIFTS 付き DEPUTY_ADMIN、または閲覧切替 ON の
     * DEPUTY_ADMIN）は未公開も含めて全量、一般メンバーは公開済みのみ。</p>
     *
     * @param teamId チームID
     * @param from   期間開始（null なら期間指定なし）
     * @param to     期間終了（null なら期間指定なし）
     * @param userId 操作者ユーザーID
     * @return シフトスケジュール一覧
     */
    public List<ShiftScheduleResponse> listSchedules(Long teamId, LocalDate from, LocalDate to, Long userId) {
        // ?teamId= 直接指定のため越境・権限不足とも 403。SYSTEM_ADMIN を特別扱いしない（§3.6）。
        if (!accessControlService.isMember(userId, teamId, TEAM)
                || accessControlService.isSupporter(userId, teamId, TEAM)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        boolean privileged = manageGate.canView(userId, teamId);
        if (from != null && to != null) {
            return scheduleService.listSchedulesByPeriod(teamId, from, to, privileged);
        }
        return scheduleService.listSchedules(teamId, privileged);
    }

    /**
     * シフトスケジュールを単体取得する。
     *
     * <p>管理者の形（ADMIN・MANAGE_SHIFTS 付き DEPUTY_ADMIN、閲覧切替 ON の DEPUTY_ADMIN。user_roles のみの ADMIN も含む）は常に可。
     * それ以外（チームロールを持たない SYSTEM_ADMIN を含む）は、所属していなければ越境として 404、
     * SUPPORTER は公開状態に依らず 403、その後に未公開なら 404（SUPPORTER に応答差で公開状態を読ませない）。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID
     * @return シフトスケジュール
     */
    public ShiftScheduleResponse getSchedule(Long id, Long userId) {
        ShiftScheduleScope scope = scheduleService.resolveScope(id);
        boolean privileged = manageGate.canView(userId, scope.teamId());
        if (!privileged) {
            // 越境は不在と同じ 404、SUPPORTER は公開状態に依らず一律 403、未公開の 404 はその後
            // （SUPPORTER に 404/403 の差で公開状態を読ませない。CMP-260826-2127 D-2127-1）。
            // 所属していない（越境）は不在と同じ 404、所属しているが在籍していない（user_roles のみの副管理者等）は 403。
            manageGate.requireMemberOrConceal(userId, scope.teamId(), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            if (accessControlService.isSupporter(userId, scope.teamId(), TEAM)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            if (scope.isHidden()) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
        }
        return scheduleService.getSchedule(id, privileged);
    }

    /**
     * シフトスケジュールを作成する（当該チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     *
     * <p>{@code ?teamId=} 直接指定のため、越境・不在・権限不足はいずれも 403（{@code COMMON_002}）のまま。</p>
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @param userId 作成者ID
     * @return 作成されたシフトスケジュール
     */
    public ShiftScheduleResponse createSchedule(Long teamId, CreateShiftScheduleRequest req, Long userId) {
        manageGate.requireManage(userId, teamId);
        return scheduleService.createSchedule(teamId, req, userId);
    }

    /**
     * シフトスケジュールを更新する（当該チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     *
     * @param id     スケジュールID
     * @param req    更新リクエスト
     * @param userId 操作者ユーザーID
     * @return 更新されたシフトスケジュール
     */
    public ShiftScheduleResponse updateSchedule(Long id, UpdateShiftScheduleRequest req, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return scheduleService.updateSchedule(id, req);
    }

    /**
     * シフトスケジュールを論理削除する（当該チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     *
     * <p>親行の FOR UPDATE は認可の後（tx 本体の中）で取る。部外者は他チームの行を掴めない。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID
     */
    public void deleteSchedule(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        scheduleService.deleteSchedule(id, userId);
    }

    /**
     * シフトスケジュールのステータスを遷移する（当該チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     *
     * @param id     スケジュールID
     * @param status 遷移先ステータス
     * @param userId 操作者ID
     * <p>CMP-261115-1821: 公開（PUBLISHED）は、認可の後・公開 TX の前に、現在の枠日付の範囲に重なる予算割当候補と
     * 予算機能の判定を取得して不変の {@link ShiftPublicationBudgetContext} に固定し、公開 TX へ値として渡す。
     * 公開 TX の中から shiftbudget・budget・feature の Repository へ到達させないため（D-3T の新規越境を作らない）。</p>
     *
     * @return 更新されたシフトスケジュール
     */
    public ShiftScheduleResponse transitionStatus(Long id, String status, Long userId) {
        ShiftScheduleScope scope = scheduleService.resolveScope(id);
        requireAdmin(scope, userId);
        if (ShiftScheduleStatus.PUBLISHED.name().equals(status)) {
            ShiftSlotDateRange range = scheduleService.findSlotDateRange(id);
            ShiftPublicationBudgetContext budgetContext = range.isEmpty()
                    ? ShiftPublicationBudgetContext.noSlots()
                    : publicationBudgetContextPort.load(scope.teamId(), range.from(), range.to());
            return scheduleService.transitionStatus(id, status, userId, budgetContext);
        }
        return scheduleService.transitionStatus(id, status, userId);
    }

    /**
     * 充足状況サマリーを取得する（閲覧扱い: ADMIN・MANAGE_SHIFTS 付き DEPUTY_ADMIN、閲覧切替 ON の DEPUTY_ADMIN）。
     *
     * <p>一般メンバーの未公開シフトは不在と同じ 404 で秘匿してから、既存の管理者ゲートを適用する。
     * SUPPORTER は全状態で既存の 403 を維持し、公開状態による応答差を新設しない。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作者ユーザーID
     * @return 日付別・ポジション別の充足状況サマリー
     */
    public ShiftScheduleSummaryResponse getScheduleSummary(Long id, Long userId) {
        ShiftScheduleScope scope = scheduleService.resolveScope(id);
        // 管理者限定でも、未公開への403と不在404との差から存在が分かる。SUPPORTERの既存403は維持する。
        if (scope.isHidden() && !manageGate.canView(userId, scope.teamId())
                && !accessControlService.isSupporter(userId, scope.teamId(), TEAM)) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
        // サマリーは閲覧扱い（閲覧切替 ON の DEPUTY_ADMIN にも許す）。
        manageGate.requireViewOrConceal(userId, scope.teamId(), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        return scheduleService.getScheduleSummary(id);
    }

    /**
     * 未提出メンバーへ手動でリマインド通知を送る（当該チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN。COLLECTING のみ）。
     *
     * <p>越境は不在と同じ 404（{@code SHIFT_001}）。是正前は実在の他チーム ID が 403、不在 ID が 404 で
     * 割れていた（存在オラクル）。Valkey の連打防止ロックは認可の後（tx 本体の先頭）で取るので、
     * 部外者は管理者の手動リマインドを塞げない。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作した管理者の ID
     * @return 送信件数と対象ユーザー ID 一覧
     */
    public ManualRemindResponse remindUnsubmitted(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return reminderService.triggerManualReminder(id, userId);
    }

    /**
     * シフトスケジュールを複製する（複製元の所属チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     *
     * @param id     複製元ID
     * @param userId 作成者ID
     * @return 複製されたシフトスケジュール
     */
    public ShiftScheduleResponse duplicateSchedule(Long id, Long userId) {
        requireAdmin(scheduleService.resolveScope(id), userId);
        return scheduleService.duplicateSchedule(id, userId);
    }

    /**
     * 管理操作の認可。越境は不在と同一の {@code SHIFT_001}、同一チーム内の権限不足は 403。
     *
     * @param scope  認可の前に解決した scope（スケジュール実体由来の teamId）
     * @param userId 操作者
     */
    private void requireAdmin(ShiftScheduleScope scope, Long userId) {
        manageGate.requireManageOrConceal(userId, scope.teamId(), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
    }
}
