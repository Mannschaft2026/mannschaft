package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * CMP-261126-1558: 退会30日後の強消去で、本人の確認通知の下書き（未送信の本文を含む）を物理削除する。
 *
 * <p>手本は {@code QuickMemoSettingsPurgeEventListener}。</p>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableNotificationDraftPurgeEventListener {

    /** {@code account_purge_completion_status.domain_name} に記録するドメイン名。 */
    public static final String DOMAIN = "confirmabledraft";

    private final ConfirmableNotificationDraftRepository draftRepository;
    private final AccountPurgeCompletionService completionService;

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の確認通知の下書きを消去する。停止すると未送信の本文が残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        draftRepository.deleteByUserId(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, DOMAIN);
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        draftRepository.deleteByUserId(userId);
        return true;
    }
}
