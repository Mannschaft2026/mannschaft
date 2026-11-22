package com.mannschaft.app.role.event;

import com.mannschaft.app.auth.event.InviteReissueRequestedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.InviteReissueRecipientResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 再招待要請を招待権限者へ通知するリスナー（CMP-261107-1833 設計 §3・§9-2）。
 *
 * <p>保留の状態を REISSUE_REQUESTED にした auth のトランザクションがコミットされた後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。通知先は {@link InviteReissueRecipientResolver}（招待権限者のみ）で、
 * 居なければ誰にも送らない。{@code @Transactional} を持たない。金型: {@code JoinRequestNotificationListener}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InviteReissueNotificationListener {

    private static final String NOTIF_SOURCE_USER = "USER";
    /** 招待先が引けなかったときの遷移先。 */
    private static final String FALLBACK_ACTION_URL = "/notifications";

    private final NotificationHelper notificationHelper;
    private final InviteReissueRecipientResolver resolver;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "CMP-261107-1833 再招待要請の通知。止めても要請の記録（DB行）は残り業務影響は無いが、"
                    + "招待権限者が要請に気づけなくなる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInviteReissueRequested(InviteReissueRequestedEvent event) {
        try {
            List<Long> inviters = resolver.findInviterUserIds(event.getScopeType(), event.getScopeId());
            if (inviters.isEmpty()) {
                log.info("再招待要請の通知先（招待権限者）がいない: scopeType={}, scopeId={}",
                        event.getScopeType(), event.getScopeId());
                return;
            }
            String actionUrl = resolver.findInviteManagementPath(event.getScopeType(), event.getScopeId())
                    .orElse(FALLBACK_ACTION_URL);
            NotificationScopeType scopeType = "TEAM".equals(event.getScopeType())
                    ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
            String title = "招待の再発行が依頼されました";
            String body = "期限切れなどで使えなくなった招待から登録した方が、新しい招待を希望しています。"
                    + "招待管理画面から招待を発行してください。";
            for (Long inviter : inviters) {
                notificationHelper.notify(inviter, NotificationType.INVITE_REISSUE_REQUESTED.name(), title, body,
                        NOTIF_SOURCE_USER, null, scopeType, event.getScopeId(), actionUrl,
                        event.getRequesterUserId());
            }
        } catch (Exception e) {
            log.error("再招待要請の通知配送に失敗しました: scopeType={}, scopeId={}",
                    event.getScopeType(), event.getScopeId(), e);
        }
    }
}
