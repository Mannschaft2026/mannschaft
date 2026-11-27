package com.mannschaft.app.skill.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * スキル検索（メンバーを資格条件で検索）の1件分のレスポンス。
 *
 * <p>JSON の項目名・値は、型を明示する前の {@code Map<String, Object>} 表現と同一。
 * 証明書のキーそのものは返さず、有無のフラグ {@code hasCertificate} のみを返す（F07.5 §6）。</p>
 */
public record SkillSearchItemResponse(
        Long memberSkillId,
        Long userId,
        String displayName,
        Long categoryId,
        String skillName,
        String issuer,
        LocalDate acquiredOn,
        LocalDate expiresAt,
        String status,
        LocalDateTime verifiedAt,
        boolean hasCertificate,
        boolean expiringSoon
) {
}
