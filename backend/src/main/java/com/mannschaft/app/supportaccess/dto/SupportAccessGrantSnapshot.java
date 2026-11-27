package com.mannschaft.app.supportaccess.dto;

import com.mannschaft.app.supportaccess.SupportAccessEndedReason;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 grant の読み取り専用の写し（Service の公開 API が Entity を返さないための値）。
 *
 * <p>トランザクションの外（表示名の解決・監査の記録）で使う。状態の書き換えは Entity 側で行い、
 * この値は遷移後の状態を運ぶだけで変更できない。</p>
 */
public record SupportAccessGrantSnapshot(
        UUID id,
        Long operatorUserId,
        Long targetUserId,
        SupportAccessStatus status,
        String requestReason,
        String referenceType,
        Long referenceId,
        Instant requestedAt,
        Instant requestExpiresAt,
        Instant decidedAt,
        Instant expiresAt,
        Instant revokedAt,
        SupportAccessEndedReason endedReason,
        boolean guardianApprovalRequired,
        Long guardianUserId,
        Instant guardianDecidedAt,
        boolean sessionActive) {

    public static SupportAccessGrantSnapshot from(SupportAccessGrantEntity g) {
        return new SupportAccessGrantSnapshot(
                g.getId(), g.getOperatorUserId(), g.getTargetUserId(), g.getStatus(), g.getRequestReason(),
                g.getReferenceType(), g.getReferenceId(), g.getRequestedAt(), g.getRequestExpiresAt(),
                g.getDecidedAt(), g.getExpiresAt(), g.getRevokedAt(), g.getEndedReason(),
                g.isGuardianApprovalRequired(), g.getGuardianUserId(), g.getGuardianDecidedAt(), g.isSessionActive());
    }

    /** 本人（asGuardian=false）または保護者（true）が許可済みか。 */
    public boolean hasApprovalBy(boolean asGuardian) {
        return asGuardian ? guardianDecidedAt != null : decidedAt != null;
    }
}
