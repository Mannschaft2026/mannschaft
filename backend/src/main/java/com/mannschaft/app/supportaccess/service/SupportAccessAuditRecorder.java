package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * F10.9 遷移の監査を、supportaccess の書込トランザクションの<b>外</b>で書く（別 Bean）。
 *
 * <p>actor（userId）は運営者、target は本人、metadata に grantId を入れる。種別名は
 * {@code SUPPORT_ACCESS_} + {@link SupportAccessAuditAction}（{@code AuditEventType} に同名で定義）。
 * 本クラスは {@code @Transactional} を持たない。呼び出し側（{@code SupportAccessGrantService}）が書込 TX のコミット後に
 * 呼ぶため、監査の書込は {@code AuditLogService} 自身の独立した書込になる。監査が他ドメイン（auth）の Repository を
 * 使うため、supportaccess の TX の内側からは呼ばない（D-3T）。</p>
 */
@Component
public class SupportAccessAuditRecorder {

    static final String EVENT_PREFIX = "SUPPORT_ACCESS_";

    private final AuditLogService auditLogService;

    public SupportAccessAuditRecorder(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public void record(SupportAccessAuditAction action, Long operatorUserId, Long targetUserId, UUID grantId) {
        String metadata = grantId == null ? null : "{\"grantId\":\"" + grantId + "\"}";
        SupportAccessContextHolder.callWithWritableConnection(() -> {
            auditLogService.recordSync(EVENT_PREFIX + action.name(), operatorUserId, targetUserId,
                    null, null, null, null, null, metadata);
            return null;
        });
    }
}
