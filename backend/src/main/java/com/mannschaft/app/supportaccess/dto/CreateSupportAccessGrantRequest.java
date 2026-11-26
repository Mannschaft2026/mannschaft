package com.mannschaft.app.supportaccess.dto;

/** F10.9 運営の依頼（理由必須 10〜1000字）。 */
public record CreateSupportAccessGrantRequest(Long targetUserId, String reason, String referenceType, Long referenceId) {
}
