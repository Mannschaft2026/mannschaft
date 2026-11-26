package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.auth.service.AuditLogService;

import java.util.UUID;

/**
 * F10.9 遷移の監査を業務 TX から独立して書く（REQUIRES_NEW・別 Bean）。actor は運営者、target は本人、metadata に grantId。試練スタブ。
 */
public class SupportAccessAuditRecorder {

    private final AuditLogService auditLogService;

    public SupportAccessAuditRecorder(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public void record(SupportAccessAuditAction action, Long operatorUserId, Long targetUserId, UUID grantId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
