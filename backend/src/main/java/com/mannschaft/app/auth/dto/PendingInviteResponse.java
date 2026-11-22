package com.mannschaft.app.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /api/v1/me/pending-invite の応答（CMP-261107-1833 設計 §2・§9-4）。招待のハッシュは含めない。
 */
public record PendingInviteResponse(
        UUID id,
        String status,
        String scopeType,
        String scopeName,
        String roleName,
        boolean joinable,
        String publicPath,
        Instant reissueRequestedAt,
        Instant retryAvailableAt) {
}
