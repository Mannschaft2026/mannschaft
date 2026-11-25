package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 招待トークン発行応答DTO。平文のトークンを載せるのは、この発行応答の1回だけ
 * （DB にはハッシュしか残らないため、後から再取得できない）。一覧用の項目は {@link InviteTokenResponse} と同じ。
 */
@Getter
@RequiredArgsConstructor
public class InviteTokenIssuedResponse {

    private final Long id;
    private final Long eventId;
    /** 平文トークン（Base64URL 43字）。一度だけ返す。 */
    private final String token;
    private final String label;
    private final Integer maxUses;
    private final Integer usedCount;
    private final LocalDateTime expiresAt;
    private final Boolean isActive;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
