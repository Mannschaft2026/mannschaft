package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F10.9 退会申請で本人の grant を即時取消する（REVOKED・WITHDRAWAL）。
 *
 * <p>退会申請の commit 後に非同期で実行する。リスナーは {@code @Transactional} を持たず、
 * 取消は {@link SupportAccessGrantService#revokeAllForWithdrawal}（REQUIRES_NEW）が独立して確定する。
 * 取消が遅れても、{@code SupportAccessGrantValidator} が退会手続中の本人を毎リクエスト拒否するため
 * 代理閲覧は成立しない（fail-closed）。退会の撤回で grant は復活しない（取消は終端）。</p>
 */
@Slf4j
@Component
public class SupportAccessWithdrawalListener {

    private final SupportAccessGrantService grantService;

    public SupportAccessWithdrawalListener(SupportAccessGrantService grantService) {
        this.grantService = grantService;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会申請された本人へのサポート閲覧の許可を取り消す。停止すると退会手続中の本人に有効な許可が残る")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWithdrawalRequested(WithdrawalRequestedEvent event) {
        int revoked = grantService.revokeAllForWithdrawal(event.getUserId());
        if (revoked > 0) {
            log.info("サポート閲覧: 退会申請により許可を取消 userId={}, 件数={}", event.getUserId(), revoked);
        }
    }
}
