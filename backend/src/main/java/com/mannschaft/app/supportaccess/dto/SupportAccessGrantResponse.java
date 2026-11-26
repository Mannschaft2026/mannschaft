package com.mannschaft.app.supportaccess.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F10.9 運営側に返す依頼・許可（本人の連絡先・メールは含めない）。
 *
 * @param sessionActive 閲覧開始済みで未終了か（ヘッダー付きリクエストが有効になりうる状態）
 */
public record SupportAccessGrantResponse(
        UUID id,
        Long targetUserId,
        String targetDisplayName,
        String status,
        String requestReason,
        String referenceType,
        Long referenceId,
        LocalDateTime requestedAt,
        LocalDateTime requestExpiresAt,
        LocalDateTime decidedAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        String endedReason,
        boolean guardianApprovalRequired,
        LocalDateTime guardianDecidedAt,
        boolean sessionActive) {
}
