package com.mannschaft.app.notification.confirmable.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CMP-261126-1558: 確認通知の下書きの更新リクエスト（PUT .../drafts/{draftId}）。
 *
 * <p>{@code baseVersion} は取得時の version。保存済みの version と一致しなければ
 * {@code CONFIRMABLE_NOTIFICATION_DRAFT_VERSION_CONFLICT}（409）になる。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ConfirmableNotificationDraftUpdateRequest extends ConfirmableNotificationDraftSaveRequest {

    private Long baseVersion;
}
