package com.mannschaft.app.event.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * イベント招待トークンの発行応答。平文 {@code token} を載せるのは発行時の一度きり
 * （DB にはハッシュしか残らないため、後から再取得できない）。
 *
 * <p>平文 {@code token} と一覧と同じ {@link InviteTokenResponse} を合成し、{@link JsonUnwrapped} で平らに出す。
 * JSON のキーは {@code id, eventId, token, label, maxUses, usedCount, expiresAt, isActive, createdBy, createdAt, updatedAt}。
 * 日時のフィールドは {@link InviteTokenResponse} だけが持つ。</p>
 *
 * @param token  平文トークン（Base64URL 43字）。発行時にだけ返す
 * @param invite 招待の概要
 */
@Schema(name = "EventInviteTokenIssuedResponse")
public record InviteTokenIssuedResponse(
        String token,
        @JsonUnwrapped InviteTokenResponse invite) {
}
