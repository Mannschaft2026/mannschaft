package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.supportaccess.service.SupportAccessRetentionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F10.9 退会30日後の強消去（AccountPurgeService の purgeTargetDomains に "supportaccess" を登録済み）。
 *
 * <p>本人が target の閲覧記録 → 許可の順に物理削除する。運営の説明責任は audit_logs（既存の匿名化方針）で残す。
 * 失敗時は完了報告せず PENDING のまま残し、GdprPurgeRetryService から {@link #retryPurge} で再試行する。</p>
 */
@Component
public class SupportAccessPurgeEventListener {

    /** account_purge_completion_status の domain 識別子。 */
    public static final String DOMAIN = "supportaccess";

    private final SupportAccessRetentionService retentionService;
    private final AccountPurgeCompletionService completion;

    public SupportAccessPurgeEventListener(SupportAccessRetentionService retentionService,
                                           AccountPurgeCompletionService completion) {
        this.retentionService = retentionService;
        this.completion = completion;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会強消去済み本人のサポート閲覧の許可と閲覧記録を消す。停止すると個人に紐づく行が残留する")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        retentionService.purgeUser(userId);
        completion.markDomainSuccess(userId, DOMAIN);
    }

    /** GdprPurgeRetryService からの手動再試行。 */
    public boolean retryPurge(Long userId) {
        retentionService.purgeUser(userId);
        return true;
    }
}
