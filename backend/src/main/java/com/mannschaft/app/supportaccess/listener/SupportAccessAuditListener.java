package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.service.SupportAccessAuditRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F10.9 代理ヘッダー付きリクエストの拒否（DENIED_REQUEST）の監査を、コミット後に audit_logs へ反映する。
 *
 * <p>拒否の記録（{@code SupportAccessLogRecorder#recordDenied}）は supportaccess の REQUIRES_NEW トランザクションで
 * 閲覧記録と監査 outbox を書く。その中から監査（auth ドメインの Repository）を呼ぶと、トランザクションが他ドメインの
 * Repository に到達するため、イベントで切り離してコミット後にここで反映する。反映に失敗しても outbox に残り、
 * 再試行バッチが拾う（ここで失敗を握りつぶして監査が消えることはない）。grant の遷移（依頼・許可など）の監査は
 * {@code SupportAccessGrantService} がコミット後に反映するため、本リスナーは DENIED_REQUEST だけを扱う。</p>
 */
@Slf4j
@Component
public class SupportAccessAuditListener {

    private final SupportAccessAuditRecorder auditRecorder;

    public SupportAccessAuditListener(SupportAccessAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "代理閲覧の拒否の監査を audit_logs へ反映する。止めても outbox に残り再試行バッチが反映するが、反映が遅れる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGrantChanged(SupportAccessGrantChangedEvent event) {
        if (event.action() != SupportAccessAuditAction.DENIED_REQUEST) {
            return;
        }
        int published = auditRecorder.relayDue();
        log.debug("サポート閲覧の拒否の監査を反映: published={}, grantId={}", published, event.grantId());
    }
}
