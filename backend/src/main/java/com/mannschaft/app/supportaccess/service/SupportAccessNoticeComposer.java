package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F10.9 サポート閲覧の通知・メールの文面を組む（第2回検分対応で通知リスナーから切り出した）。
 *
 * <p>メールは遷移と<b>同じトランザクション</b>でメール outbox に enqueue するため、宛先・locale・表示名の解決
 * （auth ドメインへの問い合わせ）は遷移の TX の<b>前</b>に {@link #prepareEmail} で済ませ、TX の中では
 * {@link PreparedEmail#toRequest} で冪等キーを付けるだけにする（supportaccess の書込 TX を他ドメインの読み取りで
 * 長引かせない）。アプリ内通知の本文はコミット後の配送時に {@link #inAppRequest} で組む。</p>
 *
 * <p>依頼理由は運営者が入力した文字列をそのままプレーンテキストで差し込む（リンク化・HTML 化しない）。メールの本文は
 * HTML エスケープし、リンクは設定画面へ遷移するだけ（メールからのワンクリック許可は成立しない）。</p>
 */
@Slf4j
@Component
public class SupportAccessNoticeComposer {

    static final String SOURCE_TYPE = "SUPPORT_ACCESS_GRANT";
    public static final String TARGET_URL = "/settings/support-access";
    private static final String OPERATOR_URL = "/system-admin/support-access";
    /** メール outbox のテンプレート種別（スルー方式: subject / body を payload に詰める）。 */
    public static final String EMAIL_TEMPLATE = "SUPPORT_ACCESS_NOTICE";
    private static final String EMAIL_SOURCE_DOMAIN = "supportaccess";
    private static final Set<String> EMAIL_LOCALES = Set.of("ja", "en", "zh", "ko", "es", "de");
    /** 本人・保護者へメールでも知らせる種別（依頼時・閲覧開始時）。 */
    public static final Set<SupportAccessNotificationKind> EMAIL_KINDS = Set.of(
            SupportAccessNotificationKind.REQUESTED_TO_TARGET,
            SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN,
            SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET);

    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final SupportAccessUserDirectory userDirectory;
    private final String baseUrl;

    public SupportAccessNoticeComposer(UserLocaleCache userLocaleCache, MessageSource messageSource,
                                       SupportAccessUserDirectory userDirectory,
                                       @Value("${app.base-url}") String baseUrl) {
        this.userLocaleCache = userLocaleCache;
        this.messageSource = messageSource;
        this.userDirectory = userDirectory;
        this.baseUrl = baseUrl;
    }

    /**
     * 遷移の TX の前に組んだメール（冪等キーだけ未確定）。TX の中で grantId・閲覧開始時刻が決まってから
     * {@link #toRequest} で enqueue する値にする。
     */
    public record PreparedEmail(Long recipientUserId, SupportAccessNotificationKind kind, String locale,
                                String toAddress, String subject, String html) {

        /** 冪等の元（source_event_id）は grantId＋種別＋宛先（閲覧開始は開始時刻）で決まる値。 */
        public EmailOutboxRequest toRequest(UUID grantId, Instant sessionStartedAt) {
            return new EmailOutboxRequest(EMAIL_TEMPLATE, locale, toAddress,
                    Map.of("subject", subject, "body", html), EMAIL_SOURCE_DOMAIN,
                    emailSourceEventId(kind, grantId, recipientUserId, sessionStartedAt), null, recipientUserId, null);
        }
    }

    /** メールの冪等の元。メール outbox は sha256(userId:テンプレート:これ) を冪等キーにするため、同じ遷移は1通になる。 */
    public static String emailSourceEventId(SupportAccessNotificationKind kind, UUID grantId, Long recipientUserId,
                                            Instant sessionStartedAt) {
        return "support-access:" + noticeKey(kind, grantId, recipientUserId, sessionStartedAt);
    }

    /** 通知・メールの冪等の共通部分（種別:grantId:宛先[:閲覧開始時刻のエポックミリ秒]）。 */
    public static String noticeKey(SupportAccessNotificationKind kind, UUID grantId, Long recipientUserId,
                                   Instant sessionStartedAt) {
        String key = kind.name() + ":" + grantId + ":" + recipientUserId;
        return sessionStartedAt == null ? key : key + ":" + sessionStartedAt.toEpochMilli();
    }

    /**
     * 本人・保護者へのメールを組む（遷移の TX の前に呼ぶ）。送れる宛先が無ければ empty（アプリ内通知は別に必ず残る）。
     *
     * @param counterpartUserId 本文に載せる相手（運営者）
     * @param childUserId       本人（保護者宛の本文に載せる）
     */
    public Optional<PreparedEmail> prepareEmail(Long recipientUserId, SupportAccessNotificationKind kind,
                                                Long counterpartUserId, Long childUserId, String reason) {
        if (!EMAIL_KINDS.contains(kind)) {
            return Optional.empty();
        }
        String email = userDirectory.findDeliverableEmail(recipientUserId).orElse(null);
        if (email == null) {
            log.warn("サポート閲覧の通知メール: 送れる宛先が無いため enqueue しない kind={}, recipientUserId={}",
                    kind, recipientUserId);
            return Optional.empty();
        }
        Locale locale = resolveLocale(recipientUserId);
        String title = title(kind, locale);
        String body = body(kind, locale, displayName(counterpartUserId), reason, displayName(childUserId));
        String settingsUrl = baseUrl + TARGET_URL;
        String linkLabel = messageSource.getMessage("notification.supportaccess.email.link", null,
                "許可・拒否・取消は設定画面で行えます（メールから直接許可することはできません）", locale);
        String html = "<p>" + HtmlUtils.htmlEscape(body) + "</p>"
                + "<p>" + HtmlUtils.htmlEscape(linkLabel) + "</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(settingsUrl) + "\">" + HtmlUtils.htmlEscape(settingsUrl) + "</a></p>";
        return Optional.of(new PreparedEmail(recipientUserId, kind, emailLocale(locale), email,
                "【Mannschaft】" + title, html));
    }

    /** アプリ内通知の配送要求を組む（コミット後の配送時）。本人・保護者宛には運営者、運営者宛には本人の表示名を入れる。 */
    public NotificationDeliveryRequest inAppRequest(Long recipientUserId, SupportAccessNotificationKind kind,
                                                    Long operatorUserId, Long targetUserId, String reason) {
        Locale locale = resolveLocale(recipientUserId);
        boolean toOperator = isToOperator(kind);
        String counterpartName = displayName(toOperator ? targetUserId : operatorUserId);
        String title = title(kind, locale);
        String body = body(kind, locale, counterpartName, reason, displayName(targetUserId));
        return new NotificationDeliveryRequest(
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
    }

    static boolean isToOperator(SupportAccessNotificationKind kind) {
        return kind == SupportAccessNotificationKind.APPROVED_TO_OPERATOR
                || kind == SupportAccessNotificationKind.DENIED_TO_OPERATOR
                || kind == SupportAccessNotificationKind.REVOKED_TO_OPERATOR;
    }

    private String title(SupportAccessNotificationKind kind, Locale locale) {
        return messageSource.getMessage(keyPrefix(kind) + ".title", null, defaultTitle(kind), locale);
    }

    private String body(SupportAccessNotificationKind kind, Locale locale, String counterpartName, String reason,
                        String childName) {
        return messageSource.getMessage(keyPrefix(kind) + ".body",
                new Object[]{counterpartName, reason, childName}, defaultBody(kind), locale);
    }

    private static String keyPrefix(SupportAccessNotificationKind kind) {
        return "notification.supportaccess." + kind.name().toLowerCase(Locale.ROOT).replace('_', '.');
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
