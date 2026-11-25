package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.event.InviteReissuedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;

/**
 * 再招待の依頼者へ、依頼の結果（新しい招待の発行・見送り）を通知するリスナー（CMP-261107-1833 PR3 設計 h）。
 *
 * <p>招待の有効化（role）・見送りの記録（auth）のトランザクションがコミットされた後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。宛先は依頼者本人だけ。文面は受信者の言語（{@code messages_*.properties} の
 * {@code notification.inviteReissued.*} / {@code notification.inviteReissueDeclined.*}）で、招待先の名前を入れる。
 * 遷移先は {@code /dashboard?pendingInvite=open}（FE が保留を取り直し、「あとで」を無視してダイアログを出す）。
 * 配送に失敗しても確定済みの状態は巻き戻さない（ERROR ログのみ）。{@code @Transactional} を持たない。
 * 金型: {@code InviteReissueNotificationListener}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingInviteRequesterNotificationListener {

    static final String ACTION_URL = "/dashboard?pendingInvite=open";
    private static final String NOTIF_SOURCE_USER = "USER";

    private final NotificationHelper notificationHelper;
    private final NameResolverService nameResolverService;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "CMP-261107-1833 PR3 再招待の発行の依頼者向け通知。止めても招待（DB行）は有効だが、"
                    + "依頼者が新しい招待に気づけなくなる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInviteReissued(InviteReissuedEvent event) {
        deliver(NotificationType.INVITE_REISSUED, "notification.inviteReissued", event.getRequesterUserId(),
                event.getScopeType(), event.getScopeId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "CMP-261107-1833 PR3 再招待の見送りの依頼者向け通知。止めても見送りの記録（DB行）は残るが、"
                    + "依頼者が結果に気づけなくなる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReissueDeclined(PendingInviteReissueDeclinedEvent event) {
        deliver(NotificationType.INVITE_REISSUE_DECLINED, "notification.inviteReissueDeclined",
                event.getRequesterUserId(), event.getScopeType(), event.getScopeId());
    }

    private void deliver(NotificationType type, String messagePrefix, Long requesterUserId, String scopeType,
                         Long scopeId) {
        try {
            String scopeName = nameResolverService.resolveScopeName(scopeType, scopeId);
            NotificationScopeType notificationScope = "TEAM".equals(scopeType)
                    ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
            notificationHelper.notifyAllLocalized(List.of(requesterUserId), type.name(), NOTIF_SOURCE_USER, null,
                    notificationScope, scopeId, ACTION_URL, null,
                    (userId, locale) -> message(locale, messagePrefix, scopeName));
        } catch (Exception e) {
            log.error("再招待の結果の依頼者通知に失敗しました: type={}, scopeType={}, scopeId={}",
                    type, scopeType, scopeId, e);
        }
    }

    private NotificationHelper.LocalizedMessage message(Locale locale, String prefix, String scopeName) {
        return new NotificationHelper.LocalizedMessage(
                messageSource.getMessage(prefix + ".title", null, locale),
                messageSource.getMessage(prefix + ".body", new Object[]{scopeName}, locale));
    }
}
