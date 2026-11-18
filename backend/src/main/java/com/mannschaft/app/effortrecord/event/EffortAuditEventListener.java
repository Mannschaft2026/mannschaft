package com.mannschaft.app.effortrecord.event;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F06.4.1 の監査ログ記録（F10.3）。業務 TX の commit 後（AFTER_COMMIT）に非同期で書くため、
 * 監査の書込失敗が業務 TX を巻き戻すことはない（親設計 §5）。metadata は ID と版だけ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffortAuditEventListener {

    private final AuditLogService auditLogService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると本人による頑張り記録の削除の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecordDeleted(EffortRecordDeletedEvent event) {
        auditLogService.record(
                AuditEventType.EFFORT_RECORD_DELETED.name(),
                event.getOwnerUserId(),
                null,
                null,
                null,
                null, null, null,
                "{\"recordId\":\"" + event.getRecordId() + "\",\"version\":" + event.getVersion() + "}");
    }
}
