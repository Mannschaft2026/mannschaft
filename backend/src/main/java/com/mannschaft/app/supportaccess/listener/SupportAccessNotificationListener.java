package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.port.SupportAccessNotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F10.9 遷移イベントを通知へ（原則5: AFTER_COMMIT リスナー）。
 *
 * <p>本人へ: 依頼時（未成年・後見下は保護者へも）・閲覧開始時。運営者へ: 許可・拒否・取消のとき。
 * 1通の失敗が他の通知を巻き込まないよう宛先ごとに独立して送る。</p>
 */
@Slf4j
@Component
public class SupportAccessNotificationListener {

    private final SupportAccessNotificationSender sender;

    public SupportAccessNotificationListener(SupportAccessNotificationSender sender) {
        this.sender = sender;
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "サポート閲覧の依頼・開始を本人へ知らせる。停止すると本人が知らないまま閲覧される状態になる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGrantChanged(SupportAccessGrantChangedEvent event) {
        switch (event.action()) {
            case REQUESTED -> {
                send(event.targetUserId(), SupportAccessNotificationKind.REQUESTED_TO_TARGET, event);
                if (event.guardianUserId() != null) {
                    send(event.guardianUserId(), SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN, event);
                }
            }
            case SESSION_STARTED ->
                    send(event.targetUserId(), SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET, event);
            case APPROVED -> send(event.operatorUserId(), SupportAccessNotificationKind.APPROVED_TO_OPERATOR, event);
            case DENIED -> send(event.operatorUserId(), SupportAccessNotificationKind.DENIED_TO_OPERATOR, event);
            case REVOKED -> send(event.operatorUserId(), SupportAccessNotificationKind.REVOKED_TO_OPERATOR, event);
            default -> {
                // CANCELLED・EXPIRED・SESSION_ENDED・DENIED_REQUEST は通知しない（監査にのみ残る）
            }
        }
    }

    private void send(Long recipientUserId, SupportAccessNotificationKind kind, SupportAccessGrantChangedEvent event) {
        try {
            sender.send(recipientUserId, kind, event.grantId());
        } catch (RuntimeException e) {
            // 通知の失敗で他の宛先・業務を止めない。TX 外（AFTER_COMMIT）なので巻き戻りで消えない。
            log.error("サポート閲覧の通知送信に失敗: kind={}, recipientUserId={}, grantId={}",
                    kind, recipientUserId, event.grantId(), e);
        }
    }
}
