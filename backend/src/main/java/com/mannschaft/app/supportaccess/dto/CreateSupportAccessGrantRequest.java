package com.mannschaft.app.supportaccess.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * F10.9 運営の依頼（理由必須 10〜1000字。長さは Service が 008 で判定する）。
 *
 * @param referenceType 依頼の参照元種別（例: FEEDBACK。任意）
 * @param referenceId   依頼の参照元 ID（任意）
 */
public record CreateSupportAccessGrantRequest(
        @NotNull Long targetUserId,
        String reason,
        @Size(max = 30) String referenceType,
        Long referenceId) {
}
