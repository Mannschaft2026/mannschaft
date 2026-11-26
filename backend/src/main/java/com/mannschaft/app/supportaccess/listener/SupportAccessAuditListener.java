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
 * F10.9 代理ヘッダー付きリクエストの拒否（DENIED_REQUEST）の監査を、コミット後に書く。
 *
 * <p>拒否の記録（{@code SupportAccessLogRecorder#recordDenied}）は supportaccess の REQUIRES_NEW トランザクション
 * で書く。その中から監査（auth ドメインの Repository）を呼ぶと、トランザクションが他ドメインの Repository に
 * 到達するため、イベントで切り離してコミット後にここで書く（Ranch の管理操作監査と同じ方式）。
 * grant の遷移（依頼・許可など）の監査は {@code SupportAccessGrantService} がコミット後に書くため、
 * 本リスナーは DENIED_REQUEST だけを扱う。</p>
 */
@Slf4j
@Component
public class SupportAccessAuditListener {

    private final SupportAccessAuditRecorder auditRecorder;

    public SupportAccessAuditListener(SupportAccessAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "代理閲覧の拒否の監査事実は再生されず、止めると運営の不正な試行の証跡が恒久的に欠落する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGrantChanged(SupportAccessGrantChangedEvent event) {
        if (event.action() != SupportAccessAuditAction.DENIED_REQUEST) {
            return;
        }
        try {
            auditRecorder.record(SupportAccessAuditAction.DENIED_REQUEST,
                    event.operatorUserId(), event.targetUserId(), event.grantId());
        } catch (RuntimeException e) {
            log.error("サポート閲覧の拒否の監査に失敗: grantId={}, operatorUserId={}",
                    event.grantId(), event.operatorUserId(), e);
        }
    }
}
