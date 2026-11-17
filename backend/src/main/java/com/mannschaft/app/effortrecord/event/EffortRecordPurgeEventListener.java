package com.mannschaft.app.effortrecord.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.effortrecord.service.EffortRecordPurgeService;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** account purge commit 後に頑張り記録の本人所有行と receipt を消し、成功した場合のみ固定 domain 完了を通知する。 */
@Component
@RequiredArgsConstructor
public class EffortRecordPurgeEventListener {

    /** account_purge_completion_status の domain 識別子。 */
    public static final String DOMAIN = "effortrecord";

    private final EffortRecordPurgeService purge;
    private final AccountPurgeCompletionService completion;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会強消去済み本人の頑張り記録と冪等 receipt を消す。停止すると私的メモを含む個人行が残留する")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purge.purgeUser(userId);
        completion.markDomainSuccess(userId, DOMAIN);
    }

    /** completion の retry_count/status 更新は GdprPurgeRetryService が担当する。 */
    public boolean retryPurge(Long userId) {
        purge.purgeUser(userId);
        return true;
    }
}
