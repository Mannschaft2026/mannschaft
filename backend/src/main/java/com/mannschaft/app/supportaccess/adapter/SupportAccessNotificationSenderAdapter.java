package com.mannschaft.app.supportaccess.adapter;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.port.SupportAccessNotificationSender;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * F10.9 通知の送り口の実装。{@code NotificationDeliveryRunner}（1件=1独立トランザクション）へ委譲する。
 *
 * <p>業務 TX の commit 後（{@code SupportAccessNotificationListener} の AFTER_COMMIT）からだけ呼ぶこと。
 * 依頼理由は運営者が入力した文字列をそのままプレーンテキストで本文に差し込む（リンク化・HTML 化しない）。
 * 通知の sourceId は Long のため grant（UUID）は actionUrl 側の画面で特定する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAccessNotificationSenderAdapter implements SupportAccessNotificationSender {

    static final String SOURCE_TYPE = "SUPPORT_ACCESS_GRANT";
    private static final String TARGET_URL = "/settings/support-access";
    private static final String OPERATOR_URL = "/system-admin/support-access";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;

    @Override
    public void send(Long recipientUserId, SupportAccessNotificationKind kind, UUID grantId) {
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
        NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
        if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
            log.warn("サポート閲覧の通知が visibility deny によりスキップ: kind={}, recipientUserId={}", kind, recipientUserId);
        }
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
