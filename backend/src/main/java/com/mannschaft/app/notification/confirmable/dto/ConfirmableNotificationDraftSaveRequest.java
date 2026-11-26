package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableDraftAudienceMode;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CMP-261126-1558: 確認通知の下書きの作成リクエスト（POST .../drafts）。
 *
 * <p>入力途中の保存のため、どの項目も省略できる（title 省略時は空文字で保存する）。
 * 上限: title 200文字・body 10,000文字・selectedTargets 200件（AC4）。
 * scope はパス、userId は JWT から取り、本文の値は信用しない。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ConfirmableNotificationDraftSaveRequest {

    @Size(max = 200)
    private String title;

    @Size(max = 10_000)
    private String body;

    private ConfirmableNotificationPriority priority;

    private Instant deadlineAt;

    private ConfirmableDraftAudienceMode audienceMode;

    @Size(max = 200)
    private List<@Valid ConfirmableTargetSpec> selectedTargets;

    private UUID selectedGroupId;

    private Long selectedTemplateId;
}
