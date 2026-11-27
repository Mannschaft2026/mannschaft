package com.mannschaft.app.supportaccess.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 本人（または保護者）に返す依頼・許可。運営者は表示名だけで、ユーザー ID・メールは含めない。
 * 依頼理由は運営者が入力した文字列をそのまま返す（表示側でプレーンテキストとして扱い、リンク化しない）。
 *
 * @param role        閲覧者の立場（TARGET=本人 / GUARDIAN=保護者）
 * @param canApprove  いま許可・拒否できるか（PENDING かつ依頼期限内で、自分の許可がまだ）
 */
public record MySupportAccessGrantResponse(
        UUID id,
        String role,
        String operatorDisplayName,
        String targetDisplayName,
        String status,
        String requestReason,
        String referenceType,
        Instant requestedAt,
        Instant requestExpiresAt,
        Instant decidedAt,
        Instant expiresAt,
        Instant revokedAt,
        String endedReason,
        boolean guardianApprovalRequired,
        boolean targetApproved,
        boolean guardianApproved,
        boolean canApprove) {
}
