package com.mannschaft.app.effortrecord.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.effortrecord.service.EffortRecordPurgeService;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * account purge commit 後に頑張り記録の本人所有行と receipt を消し、成功した場合のみ固定 domain 完了を通知する。
 *
 * <p>F06.4.1 PR-3b: 共有中の原本は tombstone になり、チーム共有の読み取り（F00）は tombstone の版・hash で続く。
 * purge は publish 側の commit の直後に<b>同じスレッドで</b>確定させる（{@code @Async} にしない）。purge-pool の待ち行列に
 * 積むと、AccountPurgedEvent の commit 後も私的な値・note・共有 mapping が残る時間窓ができ、その間に読み取り・共有の
 * 後処理が古い本文を扱い得るため。処理本体は {@link EffortRecordPurgeService#purgeUser} の REQUIRES_NEW TX で、失敗は
 * 完了を記録せず GdprPurgeRetryService の再試行で収束させる（AFTER_COMMIT の例外は発行側の TX を巻き戻さない）。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortRecordPurgeEventListener {

    /** account_purge_completion_status の domain 識別子。 */
    public static final String DOMAIN = "effortrecord";

    private final EffortRecordPurgeService purge;
    private final AccountPurgeCompletionService completion;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会強消去済み本人の頑張り記録と冪等 receipt を消す。停止すると私的メモを含む個人行が残留する")
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
