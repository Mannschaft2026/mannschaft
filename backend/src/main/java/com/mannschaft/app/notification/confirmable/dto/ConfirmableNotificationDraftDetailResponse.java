package com.mannschaft.app.notification.confirmable.dto;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableDraftAudienceMode;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CMP-261126-1558: 下書きの詳細（GET/POST/PUT .../drafts）。全項目と version を返す。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationDraftDetailResponse {

    private UUID id;

    private String title;

    private String body;

    private ConfirmableNotificationPriority priority;

    private Instant deadlineAt;

    private ConfirmableDraftAudienceMode audienceMode;

    private List<ConfirmableTargetSpec> selectedTargets;

    private UUID selectedGroupId;

    private Long selectedTemplateId;

    private Long version;

    private Instant createdAt;

    private Instant updatedAt;
}
