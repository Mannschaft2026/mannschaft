package com.mannschaft.app.supportaccess.dto;

import java.time.Instant;
import java.util.UUID;

/** F10.9 閲覧開始の応答。 */
public record SupportAccessSessionResponse(UUID grantId, Long targetUserId, String targetDisplayName, Instant expiresAt) {
}
