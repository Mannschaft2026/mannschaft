package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CMP-261126-1558: 退会30日後の強消去で、本人の確認通知の下書きを物理削除する。
 *
 * <p>手本は {@code QuickMemoSettingsPurgeEventListener}。購読注釈（{@code @TransactionalEventListener}・
 * {@code @Async("purge-pool")}・{@code @BackgroundFeaturePolicy}）と、{@code AccountPurgeService}・
 * {@code GdprSettingsPurgeRetryService}・台帳への配線は出陣で行う。</p>
 *
 * <p>試練（red）段階の骨格。</p>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableNotificationDraftPurgeEventListener {

    /** {@code account_purge_completion_status.domain_name} に記録するドメイン名。 */
    public static final String DOMAIN = "confirmabledraft";

    private final ConfirmableNotificationDraftRepository draftRepository;
    private final AccountPurgeCompletionService completionService;

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    public void onAccountPurged(AccountPurgedEvent event) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    public boolean retryPurge(Long userId) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }
}
