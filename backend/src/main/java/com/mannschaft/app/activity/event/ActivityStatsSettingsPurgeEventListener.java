package com.mannschaft.app.activity.event;

import com.mannschaft.app.activity.repository.ActivityStatsSettingsRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
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
 * 退会者の物理削除（30 日後の強匿名化）で、活動統計のスコープ別設定の最終更新者（{@code updated_by}）を NULL 化する
 * （F06.4 §15.3・CMP-261124-0159）。設定そのもの（スコープの共同業務データ）は残す。
 */
@Component
@RequiredArgsConstructor
public class ActivityStatsSettingsPurgeEventListener {

    /** 完了トラッキングのドメイン名（AccountPurgeService・GdprSettingsPurgeRetryService と一致させる）。 */
    public static final String DOMAIN = "activity";

    private final ActivityStatsSettingsRepository settingsRepository;
    private final AccountPurgeCompletionService completionService;

    /** 30 日後の強匿名化。NULL 化のコミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の ID を活動統計の設定から消す。停止すると最終更新者の ID が残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        settingsRepository.clearUpdatedBy(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, DOMAIN);
            }
        });
    }

    /** 手動再試行。呼出元はこの新規 TX のコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        settingsRepository.clearUpdatedBy(userId);
        return true;
    }
}
