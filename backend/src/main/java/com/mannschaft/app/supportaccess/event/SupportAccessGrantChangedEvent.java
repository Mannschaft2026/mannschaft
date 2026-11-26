package com.mannschaft.app.supportaccess.event;

import com.mannschaft.app.supportaccess.SupportAccessAuditAction;

import java.util.UUID;

/** F10.9 grant の遷移イベント（AFTER_COMMIT で通知へ）。 */
public record SupportAccessGrantChangedEvent(SupportAccessAuditAction action, UUID grantId,
                                             Long operatorUserId, Long targetUserId, Long guardianUserId) {
}
