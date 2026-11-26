package com.mannschaft.app.supportaccess.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** F10.9 本人向けの閲覧記録（運営者は表示名だけ。メールは出さない）。 */
public record SupportAccessLogView(UUID grantId, LocalDateTime occurredAt, String operatorDisplayName,
                                   String eventKind, String requestPath, String scopeType, Long scopeId) {
}
