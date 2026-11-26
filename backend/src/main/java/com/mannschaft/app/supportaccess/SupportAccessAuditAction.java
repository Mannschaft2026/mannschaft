package com.mannschaft.app.supportaccess;

/** F10.9 監査に残す遷移。実装役が AuditEventType.SUPPORT_ACCESS_*（ADMIN_ACTION 区分）へ対応付ける。 */
public enum SupportAccessAuditAction {
    REQUESTED,
    APPROVED,
    DENIED,
    REVOKED,
    CANCELLED,
    EXPIRED,
    SESSION_STARTED,
    SESSION_ENDED,
    DENIED_REQUEST
}
