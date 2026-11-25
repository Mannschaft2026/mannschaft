package com.mannschaft.app.shift.event;

/**
 * シフト変更依頼が作成（OPEN）された事実を表すドメインイベント。
 *
 * <p>{@code ShiftChangeRequestService#create} が業務トランザクション内で publish し、
 * {@link ShiftChangeRequestNotificationListener} が {@code AFTER_COMMIT} で管理者宛の通知を配送する。
 * ロールバックされれば通知は作られない。仕様: {@code docs/features/F03.5_shift/02_api_design.md}
 * （変更依頼 POST）。</p>
 *
 * @param changeRequestId  変更依頼 ID（通知の source）
 * @param scheduleId       対象スケジュール ID（遷移先の組み立て用）
 * @param teamId           スケジュールの所属チーム ID（宛先の管理者はこのチームに限る）
 * @param requesterUserId  依頼者のユーザー ID
 */
public record ShiftChangeRequestCreatedEvent(
        Long changeRequestId,
        Long scheduleId,
        Long teamId,
        Long requesterUserId) {
}
