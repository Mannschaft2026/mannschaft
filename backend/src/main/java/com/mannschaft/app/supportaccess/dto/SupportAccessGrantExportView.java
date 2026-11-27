package com.mannschaft.app.supportaccess.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 GDPR エクスポート用のサポート閲覧の依頼・許可（運営者は表示名のみ・メールは含めない）。
 *
 * @param role 本人として受けた依頼（{@link #ROLE_TARGET}）か、保護者として関わった依頼（{@link #ROLE_GUARDIAN}）か
 */
public record SupportAccessGrantExportView(
        UUID grantId,
        String role,
        String status,
        String requestReason,
        String operatorDisplayName,
        String referenceType,
        Long referenceId,
        Instant requestedAt,
        Instant requestExpiresAt,
        Instant decidedAt,
        String denyComment,
        Instant guardianDecidedAt,
        Instant expiresAt,
        Instant revokedAt,
        String endedReason,
        Instant lastSessionStartedAt) {

    public static final String ROLE_TARGET = "TARGET";
    public static final String ROLE_GUARDIAN = "GUARDIAN";
}
