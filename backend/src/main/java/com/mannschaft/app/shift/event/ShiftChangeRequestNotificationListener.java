package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.shift.ChangeRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * シフト変更依頼の通知（アプリ内 ＋ プッシュ）の配送リスナー。
 *
 * <p>業務トランザクション（作成・審査・取り下げ）が commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。{@link ShiftSwapExpiredNotificationListener} と同型
 * （受信者ごとの内側 try で隔離し、1 人ぶんの失敗が他の受信者・業務の確定結果を巻き込まない）。
 * 仕様: {@code docs/features/F03.5_shift/01_db_design.md}（shift_change_requests「通知」）。</p>
 *
 * <h2>宛先と種別</h2>
 * <ul>
 *   <li>作成（OPEN）: {@code SHIFT_CHANGE_REQUEST_CREATED} → そのチームの管理者全員
 *       （ADMIN ＋ MANAGE_SHIFTS を持つ DEPUTY_ADMIN）</li>
 *   <li>受諾・却下: {@code SHIFT_CHANGE_REQUEST_REVIEWED} → 依頼者のみ。審査コメントを本文に含める</li>
 *   <li>取り下げ: {@code SHIFT_CHANGE_REQUEST_WITHDRAWN} → そのチームの管理者全員</li>
 * </ul>
 *
 * <h2>自己通知の扱い</h2>
 * <p>管理者が自分で出した依頼（作成）・自分で取り下げた依頼（取り下げ）では、依頼者本人を
 * 「管理者へ」の宛先から除く（自分の操作を自分に通知しない）。受諾・却下の宛先は依頼者なので、
 * 審査者本人が依頼者になることは自己審査禁止（SHIFT_045）により起きない。</p>
 *
 * <h2>漏えい防止</h2>
 * <p>管理者の解決は必ず「イベントに載った teamId（スケジュール実体由来）」の管理者に限る。
 * 遷移先は {@code /teams/{slug}/shifts/{scheduleId}/change-requests}（FE の変更依頼画面）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftChangeRequestNotificationListener {

    static final String TYPE_CREATED = "SHIFT_CHANGE_REQUEST_CREATED";
    static final String TYPE_REVIEWED = "SHIFT_CHANGE_REQUEST_REVIEWED";
    static final String TYPE_WITHDRAWN = "SHIFT_CHANGE_REQUEST_WITHDRAWN";
    static final String SOURCE_TYPE = "SHIFT_CHANGE_REQUEST";
    /** 副管理者に要求する権限（仕様: ADMIN ＋ DEPUTY_ADMIN の MANAGE_SHIFTS 保有者）。 */
    static final String MANAGE_SHIFTS = "MANAGE_SHIFTS";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final RoleService roleService;
    private final NameResolverService nameResolverService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_SHIFT_ENABLED"},
            reason = "シフトは棚卸し台帳で beta=停止・gate_key=FEATURE_SHIFT_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に変更依頼の通知だけが管理者へ飛ぶことを避ける。依頼の作成自体は確定済みで、"
                    + "ドロップされるのは通知だけであるため整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ShiftChangeRequestCreatedEvent event) {
        List<Long> recipients = adminRecipients(event.teamId(), event.requesterUserId());
        String requesterName = requesterName(event.requesterUserId());
        String actionUrl = actionUrl(event.teamId(), event.scheduleId());
        deliver(TYPE_CREATED, event.changeRequestId(), event.teamId(), recipients,
                locale -> new NotificationDeliveryRequest(
                        null, TYPE_CREATED, NotificationPriority.NORMAL,
                        msg("notification.shift.changeRequestCreated.title", locale),
                        msg("notification.shift.changeRequestCreated.body", locale,
                                requesterName != null ? requesterName
                                        : msg("notification.shift.changeRequest.unknownRequester", locale)),
                        SOURCE_TYPE, event.changeRequestId(), NotificationScopeType.TEAM, event.teamId(),
                        actionUrl, event.requesterUserId()));
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_SHIFT_ENABLED"},
            reason = "シフトは棚卸し台帳で beta=停止・gate_key=FEATURE_SHIFT_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に変更依頼の審査結果通知だけが依頼者へ飛ぶことを避ける。審査自体は確定済みで、"
                    + "ドロップされるのは通知だけであるため整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewed(ShiftChangeRequestReviewedEvent event) {
        if (event.decision() != ChangeRequestStatus.ACCEPTED && event.decision() != ChangeRequestStatus.REJECTED) {
            return;
        }
        String key = event.decision() == ChangeRequestStatus.ACCEPTED
                ? "notification.shift.changeRequestReviewed.accepted"
                : "notification.shift.changeRequestReviewed.rejected";
        String note = event.reviewComment() == null ? "" : event.reviewComment().strip();
        String actionUrl = actionUrl(event.teamId(), event.scheduleId());
        deliver(TYPE_REVIEWED, event.changeRequestId(), event.teamId(),
                List.of(event.requesterUserId()),
                locale -> new NotificationDeliveryRequest(
                        null, TYPE_REVIEWED, NotificationPriority.NORMAL,
                        msg(key + ".title", locale),
                        note.isEmpty() ? msg(key + ".body", locale) : msg(key + ".bodyWithNote", locale, note),
                        SOURCE_TYPE, event.changeRequestId(), NotificationScopeType.TEAM, event.teamId(),
                        actionUrl, event.reviewerUserId()));
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_SHIFT_ENABLED"},
            reason = "シフトは棚卸し台帳で beta=停止・gate_key=FEATURE_SHIFT_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に変更依頼の取り下げ通知だけが管理者へ飛ぶことを避ける。取り下げ自体は確定済みで、"
                    + "ドロップされるのは通知だけであるため整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWithdrawn(ShiftChangeRequestWithdrawnEvent event) {
        List<Long> recipients = adminRecipients(event.teamId(), event.requesterUserId());
        String requesterName = requesterName(event.requesterUserId());
        String actionUrl = actionUrl(event.teamId(), event.scheduleId());
        deliver(TYPE_WITHDRAWN, event.changeRequestId(), event.teamId(), recipients,
                locale -> new NotificationDeliveryRequest(
                        null, TYPE_WITHDRAWN, NotificationPriority.NORMAL,
                        msg("notification.shift.changeRequestWithdrawn.title", locale),
                        msg("notification.shift.changeRequestWithdrawn.body", locale,
                                requesterName != null ? requesterName
                                        : msg("notification.shift.changeRequest.unknownRequester", locale)),
                        SOURCE_TYPE, event.changeRequestId(), NotificationScopeType.TEAM, event.teamId(),
                        actionUrl, event.requesterUserId()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 配送
    // ═════════════════════════════════════════════════════════════════════

    /** 管理者宛の受信者を解決する。依頼者本人は除く（自己通知しない）。解決失敗は空リストで握らず例外を上げる。 */
    private List<Long> adminRecipients(Long teamId, Long requesterUserId) {
        return roleService.getAdminAndPermittedDeputyUserIdsByTeamId(teamId, MANAGE_SHIFTS).stream()
                .filter(id -> !id.equals(requesterUserId))
                .toList();
    }

    /** 依頼者の表示名。解決できない（退会者等）場合は null。 */
    private String requesterName(Long requesterUserId) {
        try {
            return nameResolverService.resolveUserDisplayNames(List.of(requesterUserId)).get(requesterUserId);
        } catch (Exception e) {
            log.warn("変更依頼通知の依頼者名の解決に失敗（匿名の文言で継続）: requesterUserId={}, error={}",
                    requesterUserId, e.getMessage());
            return null;
        }
    }

    /** FE の変更依頼画面（{@code /teams/{slug}/shifts/{scheduleId}/change-requests}）。slug 不明時は通知一覧へ。 */
    private String actionUrl(Long teamId, Long scheduleId) {
        String slug = null;
        try {
            slug = nameResolverService.resolveScopeSlug("TEAM", teamId);
        } catch (Exception e) {
            log.warn("変更依頼通知の遷移先 slug の解決に失敗（通知一覧へ）: teamId={}, error={}", teamId, e.getMessage());
        }
        return slug != null ? "/teams/" + slug + "/shifts/" + scheduleId + "/change-requests" : "/notifications";
    }

    private void deliver(String type, Long changeRequestId, Long teamId, List<Long> recipients,
                         Function<Locale, NotificationDeliveryRequest> template) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("変更依頼通知の locale 一括解決に失敗（既定 locale で継続）: type={}, changeRequestId={}, error={}",
                    type, changeRequestId, e.getMessage());
            locales = Map.of();
        }

        int denied = 0;
        int failed = 0;
        for (Long recipientUserId : recipients) {
            try {
                NotificationDeliveryRequest base = template.apply(
                        Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja")));
                NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                        recipientUserId, base.notificationType(), base.priority(), base.title(), base.body(),
                        base.sourceType(), base.sourceId(), base.scopeType(), base.scopeId(),
                        base.actionUrl(), base.actorId());
                if (notificationDeliveryRunner.sendOne(request) == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    denied++;
                    log.warn("変更依頼通知が visibility deny によりスキップされました: recipientUserId={}, type={}, "
                            + "changeRequestId={}", recipientUserId, type, changeRequestId);
                }
            } catch (Exception e) {
                failed++;
                log.error("変更依頼通知の配送に失敗しました: recipientUserId={}, type={}, changeRequestId={}",
                        recipientUserId, type, changeRequestId, e);
            }
        }
        if (failed > 0 || denied > 0) {
            log.warn("変更依頼通知の一括配送結果: type={}, changeRequestId={}, teamId={}, total={}, failed={}, denied={}",
                    type, changeRequestId, teamId, recipients.size(), failed, denied);
        }
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args.length == 0 ? null : args, key, locale);
    }
}
