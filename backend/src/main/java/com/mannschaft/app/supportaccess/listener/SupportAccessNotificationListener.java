package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.event.SupportAccessNotificationRelayRequestedEvent;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer;
import com.mannschaft.app.supportaccess.service.SupportAccessNotificationOutboxStore;
import com.mannschaft.app.supportaccess.service.SupportAccessNotificationOutboxStore.PendingNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * F10.9 アプリ内通知 outbox の配送（原則5: AFTER_COMMIT リスナー）。
 *
 * <p><b>第2回検分対応</b>: 以前は本リスナーがコミット後に通知とメールを作っていたため、再起動・プールの拒否・例外で
 * 閲覧開始の通知が消え、メールの冪等キーも毎回の乱数だった。いまは遷移と<b>同じトランザクション</b>で
 * アプリ内通知の outbox（support_access_notification_outbox）に宛先ごとの行、メール outbox に決定的な冪等キーの
 * メール行を書く（{@code SupportAccessGrantTransitionService}）。本リスナーは outbox 行を配送するだけで、
 * 配送できなかった行は未配送のまま残り、再試行バッチ（{@link SupportAccessNotificationRelayRequestedEvent}）が拾う。</p>
 *
 * <p>配送（{@code NotificationDeliveryRunner#sendOne}・1件=1独立トランザクション）は、通知境界の番人が求める正規形
 * （AFTER_COMMIT のリスナー、またはそこから同一クラス内の無修飾呼び出しで届く private ヘルパ）に合わせ、
 * このリスナー内に置く。1件の失敗で他の宛先を止めない（行ごとに失敗を outbox に記録して再試行に回す）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAccessNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessNotificationOutboxStore notificationOutboxStore;
    private final SupportAccessNoticeComposer composer;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "サポート閲覧の依頼・開始を本人へ知らせる。停止しても outbox に残り再試行バッチが配送するが、本人が知るのが遅れる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGrantChanged(SupportAccessGrantChangedEvent event) {
        if (event.grantId() == null) {
            return;
        }
        deliverAll(notificationOutboxStore.leaseForGrant(event.grantId()));
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "配送できなかったサポート閲覧の通知を再配送する。停止すると本人が閲覧の依頼・開始を知らないままになりうる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRelayRequested(SupportAccessNotificationRelayRequestedEvent event) {
        deliverAll(notificationOutboxStore.leaseDue(SupportAccessNotificationOutboxStore.RELAY_BATCH_LIMIT));
    }

    private void deliverAll(List<PendingNotification> rows) {
        for (PendingNotification row : rows) {
            try {
                deliver(row);
                notificationOutboxStore.markPublished(row.id());
            } catch (RuntimeException e) {
                // 失敗は outbox に記録して再試行バッチに回す（握りつぶさない。行は未配送のまま残る）
                log.error("サポート閲覧の通知の配送に失敗（再試行に回す）: kind={}, recipientUserId={}, grantId={}, attempt={}",
                        row.notificationKind(), row.recipientUserId(), row.grantId(), row.attemptCount(), e);
                notificationOutboxStore.markFailed(row.id(), e.toString());
            }
        }
    }

    private void deliver(PendingNotification row) {
        SupportAccessGrantEntity grant = grantRepository.findById(row.grantId()).orElse(null);
        if (grant == null) {
            // grant が保存期間・退会の強消去で消えた。通知の対象が無いため配送済みとして閉じる
            log.warn("サポート閲覧の通知: grant が見つからないため送信しない grantId={}, kind={}",
                    row.grantId(), row.notificationKind());
            return;
        }
        SupportAccessNotificationKind kind = SupportAccessNotificationKind.valueOf(row.notificationKind());
        NotificationDeliveryRequest request = composer.inAppRequest(row.recipientUserId(), kind,
                grant.getOperatorUserId(), grant.getTargetUserId(), grant.getRequestReason());
        NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
        if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
            log.warn("サポート閲覧の通知が visibility deny によりスキップ: kind={}, recipientUserId={}",
                    kind, row.recipientUserId());
        }
    }
}
