package com.mannschaft.app.role.event;

import com.mannschaft.app.auth.event.InviteReissueRequestedEvent;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.InviteReissueRecipientResolver;
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
 * 再招待要請を招待権限者へ通知するリスナー（CMP-261107-1833 設計 §3・§9-2）。
 *
 * <p>保留の状態を REISSUE_REQUESTED にした auth のトランザクションがコミットされた後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。通知先は {@link InviteReissueRecipientResolver}（招待権限者のみ）で、
 * 居なければ誰にも送らない。{@code @Transactional} を持たない。金型: {@code JoinRequestNotificationListener}。</p>
 *
 * <p>本文には要請者の<b>表示名</b>と招待先（チーム・組織）の名前を入れる（誰の依頼か分からないと ADMIN が渡せない）。
 * メールアドレスなど表示名以外の個人情報は載せない。文面は受信者ごとの言語（{@code messages_*.properties} の
 * {@code notification.inviteReissueRequested.*}）で組み立てる。表示名は利用者入力なのでログへは出さない
 * （docs/security/05 §7: ログインジェクション対策の対象）。遷移先は対象招待先の招待管理画面。</p>
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
    private final NameResolverService nameResolverService;
    private final MessageSource messageSource;

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
            String requesterName = nameResolverService.resolveUserDisplayName(event.getRequesterUserId());
            String scopeName = nameResolverService.resolveScopeName(event.getScopeType(), event.getScopeId());
            notificationHelper.notifyAllLocalized(inviters, NotificationType.INVITE_REISSUE_REQUESTED.name(),
                    NOTIF_SOURCE_USER, null, scopeType, event.getScopeId(), actionUrl, event.getRequesterUserId(),
                    (userId, locale) -> message(locale, requesterName, scopeName));
        } catch (Exception e) {
            log.error("再招待要請の通知配送に失敗しました: scopeType={}, scopeId={}",
                    event.getScopeType(), event.getScopeId(), e);
        }
    }

    private NotificationHelper.LocalizedMessage message(Locale locale, String requesterName, String scopeName) {
        return new NotificationHelper.LocalizedMessage(
                messageSource.getMessage("notification.inviteReissueRequested.title", null, locale),
                messageSource.getMessage("notification.inviteReissueRequested.body",
                        new Object[]{requesterName, scopeName}, locale));
    }
}
