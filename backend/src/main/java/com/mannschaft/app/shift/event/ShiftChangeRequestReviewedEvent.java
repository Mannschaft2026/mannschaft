package com.mannschaft.app.shift.event;

import com.mannschaft.app.shift.ChangeRequestStatus;

/**
 * シフト変更依頼が受諾（ACCEPTED）または却下（REJECTED）された事実を表すドメインイベント。
 *
 * <p>{@code ShiftChangeRequestService#review} が業務トランザクション内で publish し、
 * {@link ShiftChangeRequestNotificationListener} が {@code AFTER_COMMIT} で依頼者宛の通知を配送する。</p>
 *
 * @param changeRequestId 変更依頼 ID（通知の source）
 * @param scheduleId      対象スケジュール ID
 * @param teamId          スケジュールの所属チーム ID
 * @param requesterUserId 依頼者（通知の宛先）
 * @param reviewerUserId  審査者（通知の実行者。依頼者本人は審査できない）
 * @param decision        審査結果（ACCEPTED / REJECTED）
 * @param reviewComment   審査コメント（空・null 可。本文に含める）
 */
public record ShiftChangeRequestReviewedEvent(
        Long changeRequestId,
        Long scheduleId,
        Long teamId,
        Long requesterUserId,
        Long reviewerUserId,
        ChangeRequestStatus decision,
        String reviewComment) {
}
