package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F10.9 遷移イベントを通知へ（原則5: AFTER_COMMIT リスナー）。
 *
 * <p>本人へ: 依頼時（未成年・後見下は保護者へも）・閲覧開始時。運営者へ: 許可・拒否・取消のとき。
 * 1通の失敗が他の通知を巻き込まないよう宛先ごとに独立して送る。</p>
 *
 * <p>配送（{@code NotificationDeliveryRunner#sendOne}・1件=1独立トランザクション）は、通知境界の番人が求める正規形
 * （AFTER_COMMIT のリスナー、またはそこから同一クラス内の無修飾呼び出しで届く private ヘルパ）に合わせ、
 * このリスナー内に置く。依頼理由は運営者が入力した文字列をそのままプレーンテキストで本文に差し込む（リンク化・HTML 化しない）。
 * 通知の sourceId は Long のため grant（UUID）は actionUrl 側の画面で特定する。</p>
 *
 * <p><b>本人・保護者への通知は安全上必須</b>（{@code MandatoryNotificationTypes}）: アプリ内通知は通知設定で種別を OFF に
 * していても配信され、加えてメール（F09.18 メール outbox・{@value #EMAIL_TEMPLATE}）でも依頼時・閲覧開始時に知らせる。
 * メールの依頼理由は HTML エスケープしたプレーンテキストで載せ、リンクは設定画面（{@value #TARGET_URL}）へ遷移するだけ
 * （メールからのワンクリック許可は成立しない。許可には設定画面での再認証が要る）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAccessNotificationListener {

    static final String SOURCE_TYPE = "SUPPORT_ACCESS_GRANT";
    static final String TARGET_URL = "/settings/support-access";
    private static final String OPERATOR_URL = "/system-admin/support-access";
    /** メール outbox のテンプレート種別（スルー方式: subject / body を payload に詰める）。 */
    static final String EMAIL_TEMPLATE = "SUPPORT_ACCESS_NOTICE";
    private static final String EMAIL_SOURCE_DOMAIN = "supportaccess";
    private static final Set<String> EMAIL_LOCALES = Set.of("ja", "en", "zh", "ko", "es", "de");
    /** 本人・保護者へメールでも知らせる種別（依頼時・閲覧開始時）。 */
    private static final Set<SupportAccessNotificationKind> EMAIL_KINDS = Set.of(
            SupportAccessNotificationKind.REQUESTED_TO_TARGET,
            SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN,
            SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET);

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;
    private final EmailOutboxService emailOutboxService;

    @Value("${app.base-url}")
    private String baseUrl;

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
            deliver(recipientUserId, kind, event.grantId());
        } catch (RuntimeException e) {
            // 通知の失敗で他の宛先・業務を止めない。TX 外（AFTER_COMMIT）なので巻き戻りで消えない。
            log.error("サポート閲覧の通知送信に失敗: kind={}, recipientUserId={}, grantId={}",
                    kind, recipientUserId, event.grantId(), e);
        }
    }

    private void deliver(Long recipientUserId, SupportAccessNotificationKind kind, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId).orElse(null);
        if (grant == null) {
            log.warn("サポート閲覧の通知: grant が見つからないため送信しない grantId={}, kind={}", grantId, kind);
            return;
        }
        Locale locale = resolveLocale(recipientUserId);
        boolean toOperator = kind == SupportAccessNotificationKind.APPROVED_TO_OPERATOR
                || kind == SupportAccessNotificationKind.DENIED_TO_OPERATOR
                || kind == SupportAccessNotificationKind.REVOKED_TO_OPERATOR;
        // 本人・保護者宛には運営者の表示名、運営者宛には本人の表示名を入れる（メールは出さない）
        String counterpartName = displayName(toOperator ? grant.getTargetUserId() : grant.getOperatorUserId());
        String childName = displayName(grant.getTargetUserId());

        String keyPrefix = "notification.supportaccess." + kind.name().toLowerCase(Locale.ROOT).replace('_', '.');
        String title = messageSource.getMessage(keyPrefix + ".title", null, defaultTitle(kind), locale);
        String body = messageSource.getMessage(keyPrefix + ".body",
                new Object[]{counterpartName, grant.getRequestReason(), childName}, defaultBody(kind), locale);

        NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                recipientUserId,
                "SUPPORT_ACCESS_" + kind.name(),
                toOperator ? NotificationPriority.NORMAL : NotificationPriority.HIGH,
                title,
                body,
                SOURCE_TYPE,
                null,
                NotificationScopeType.PERSONAL,
                recipientUserId,
                toOperator ? OPERATOR_URL : TARGET_URL,
                null);
        // アプリ内通知とメールは独立に送る（片方の失敗でもう片方を止めない）
        RuntimeException failure = null;
        try {
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("サポート閲覧の通知が visibility deny によりスキップ: kind={}, recipientUserId={}", kind, recipientUserId);
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        if (EMAIL_KINDS.contains(kind)) {
            try {
                enqueueEmail(recipientUserId, kind, grantId, title, body, locale);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 本人・保護者へのメール（F09.18 メール outbox）。通知設定の OFF に関わらず送る（安全上必須）。
     * 本文は HTML エスケープしたプレーンテキスト（依頼理由に HTML・リンクを混ぜさせない）と、設定画面へのリンクだけ。
     */
    private void enqueueEmail(Long recipientUserId, SupportAccessNotificationKind kind, UUID grantId,
                              String title, String body, Locale locale) {
        String email = userDirectory.findDeliverableEmail(recipientUserId).orElse(null);
        if (email == null) {
            log.warn("サポート閲覧の通知メール: 送れる宛先が無いため enqueue しない kind={}, recipientUserId={}",
                    kind, recipientUserId);
            return;
        }
        String settingsUrl = baseUrl + TARGET_URL;
        String linkLabel = messageSource.getMessage("notification.supportaccess.email.link", null,
                "許可・拒否・取消は設定画面で行えます（メールから直接許可することはできません）", locale);
        String html = "<p>" + HtmlUtils.htmlEscape(body) + "</p>"
                + "<p>" + HtmlUtils.htmlEscape(linkLabel) + "</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(settingsUrl) + "\">" + HtmlUtils.htmlEscape(settingsUrl) + "</a></p>";
        emailOutboxService.enqueue(new EmailOutboxRequest(
                EMAIL_TEMPLATE,
                emailLocale(locale),
                email,
                Map.of("subject", "【Mannschaft】" + title, "body", html),
                EMAIL_SOURCE_DOMAIN,
                "support-access:" + kind.name() + ":" + grantId + ":" + UUID.randomUUID(),
                null,
                recipientUserId,
                null));
    }

    private static String emailLocale(Locale locale) {
        String lang = locale == null ? "ja" : locale.getLanguage();
        return EMAIL_LOCALES.contains(lang) ? lang : "ja";
    }

    private Locale resolveLocale(Long userId) {
        try {
            return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
        } catch (Exception e) {
            log.warn("サポート閲覧の通知: locale 解決に失敗（既定 locale で継続）userId={}, error={}", userId, e.getMessage());
            return Locale.JAPANESE;
        }
    }

    private String displayName(Long userId) {
        return userDirectory.findUser(userId).map(SupportAccessUserSnapshot::displayName).orElse("-");
    }

    private static String defaultTitle(SupportAccessNotificationKind kind) {
        return switch (kind) {
            case REQUESTED_TO_TARGET -> "サポート閲覧の許可依頼";
            case REQUESTED_TO_GUARDIAN -> "お子様のサポート閲覧の許可依頼";
            case SESSION_STARTED_TO_TARGET -> "サポート閲覧が開始されました";
            case APPROVED_TO_OPERATOR -> "サポート閲覧が許可されました";
            case DENIED_TO_OPERATOR -> "サポート閲覧が拒否されました";
            case REVOKED_TO_OPERATOR -> "サポート閲覧の許可が取り消されました";
        };
    }

    private static String defaultBody(SupportAccessNotificationKind kind) {
        return switch (kind) {
            case REQUESTED_TO_TARGET, REQUESTED_TO_GUARDIAN -> "サポート閲覧の許可が依頼されました。";
            case SESSION_STARTED_TO_TARGET -> "運営担当者があなたの画面の閲覧を開始しました。";
            case APPROVED_TO_OPERATOR -> "サポート閲覧が許可されました。";
            case DENIED_TO_OPERATOR -> "サポート閲覧が拒否されました。";
            case REVOKED_TO_OPERATOR -> "サポート閲覧の許可が取り消されました。";
        };
    }
}
