package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * F10.9 遷移の監査を業務 TX から独立して書く（REQUIRES_NEW・別 Bean）。
 *
 * <p>actor（userId）は運営者、target は本人、metadata に grantId を入れる。種別名は
 * {@code SUPPORT_ACCESS_} + {@link SupportAccessAuditAction}（{@code AuditEventType} に同名で定義）。
 * 業務 TX が失敗・巻き戻しになっても監査は残る。</p>
 */
@Component
public class SupportAccessAuditRecorder {

    static final String EVENT_PREFIX = "SUPPORT_ACCESS_";

    private final AuditLogService auditLogService;

    public SupportAccessAuditRecorder(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SupportAccessAuditAction action, Long operatorUserId, Long targetUserId, UUID grantId) {
        String metadata = grantId == null ? null : "{\"grantId\":\"" + grantId + "\"}";
        SupportAccessContextHolder.callWithWritableConnection(() -> {
            auditLogService.recordSync(EVENT_PREFIX + action.name(), operatorUserId, targetUserId,
                    null, null, null, null, null, metadata);
            return null;
        });
    }
}
