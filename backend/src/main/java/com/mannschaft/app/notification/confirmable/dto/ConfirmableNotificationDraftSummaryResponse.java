package com.mannschaft.app.notification.confirmable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * CMP-261126-1558: 下書き一覧（GET .../drafts）の1要素。
 *
 * <p>{@code bodyExcerpt} は本文の先頭100文字。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableNotificationDraftSummaryResponse {

    private UUID id;

    private String title;

    private String bodyExcerpt;

    private Instant updatedAt;
}
