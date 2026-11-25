package com.mannschaft.app.shift.event;

/**
 * シフト変更依頼が依頼者本人によって取り下げ（WITHDRAWN）られた事実を表すドメインイベント。
 *
 * <p>{@code ShiftChangeRequestService#withdraw} が業務トランザクション内で publish し、
 * {@link ShiftChangeRequestNotificationListener} が {@code AFTER_COMMIT} で管理者宛の通知を配送する。</p>
 *
 * @param changeRequestId 変更依頼 ID（通知の source）
 * @param scheduleId      対象スケジュール ID
 * @param teamId          スケジュールの所属チーム ID（宛先の管理者はこのチームに限る）
 * @param requesterUserId 依頼者のユーザー ID（管理者でも自己通知は送らない）
 */
public record ShiftChangeRequestWithdrawnEvent(
        Long changeRequestId,
        Long scheduleId,
        Long teamId,
        Long requesterUserId) {
}
