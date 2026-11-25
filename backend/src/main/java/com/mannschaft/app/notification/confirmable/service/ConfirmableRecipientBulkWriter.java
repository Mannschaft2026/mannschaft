package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.token.PlaintextTokenDebt;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CMP-261002-1559: 確認通知の受信者行・アプリ内通知を「500人ずつ多値 INSERT」で書き込む共通部品。
 *
 * <p>非同期 fan-out（{@link ConfirmableFanoutChunkSink}）と同期送信
 * （{@link ConfirmableNotificationService#send}）の両方がこの部品を使う。受信者行・
 * {@code notifications} の主キーは IDENTITY のため JPA の {@code saveAll} は1人1 INSERT になる。
 * これを避けるため {@link JdbcTemplate} で 1 文＝複数行の多値 INSERT を発行し、発行文数を
 * 受信者数ではなくチャンク数に比例させる。</p>
 *
 * <ul>
 *   <li>1 回の呼び出しに渡す受信者は最大 {@link #CHUNK_SIZE} 人（呼び出し側で刻む）。</li>
 *   <li>受信者数に上限は設けない（500 は1チャンクの単位であり全体の上限ではない。F04.9 §4）。</li>
 *   <li>トランザクションは呼び出し側のものに参加する（本部品は独自に境界を張らない）。</li>
 *   <li>リマインド分数の解決は呼び出し側の責務（本部品は解決済みの値を受け取る）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableRecipientBulkWriter {

    /** 1 回の多値 INSERT で扱う受信者数の上限（全体の上限ではない）。 */
    public static final int CHUNK_SIZE = 500;

    private static final String SOURCE_TYPE = "CONFIRMABLE_NOTIFICATION";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 指定の確認通知に既に受信者行がある user_id を、1 文の SELECT で求める（再開・再発火時の二重防止）。
     *
     * @param notificationId 確認通知ID
     * @param userIds        候補受信者（最大 {@link #CHUNK_SIZE} 人）
     * @return 既に受信者行がある user_id
     */
    public Set<Long> findExistingRecipientUserIds(Long notificationId, List<Long> userIds) {
        Set<Long> distinctCandidates = new HashSet<>(userIds);
        if (distinctCandidates.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", distinctCandidates.stream().map(id -> "?").toList());
        Object[] args = new Object[distinctCandidates.size() + 1];
        args[0] = notificationId;
        int i = 1;
        for (Long id : distinctCandidates) {
            args[i++] = id;
        }
        List<Long> rows = jdbcTemplate.query(
                "SELECT user_id FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ? AND user_id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getLong("user_id"), args);
        return new HashSet<>(rows);
    }

    /**
     * 受信者行を確認トークン付きで 1 文の多値 INSERT で作成する。
     *
     * @param notificationId                確認通知ID
     * @param userIds                       新規受信者（重複なし・最大 {@link #CHUNK_SIZE} 人）
     * @param resolvedFirstReminderMinutes  解決済みの1回目リマインド分数（非正規化して保存する）
     * @param resolvedSecondReminderMinutes 解決済みの2回目リマインド分数（非正規化して保存する）
     * @return user_id → 確認トークン
     */
    @PlaintextTokenDebt(cmp = "CMP-260826-1457")
    public Map<Long, String> insertRecipients(Long notificationId, List<Long> userIds,
            int resolvedFirstReminderMinutes, int resolvedSecondReminderMinutes) {
        Map<Long, String> tokens = new HashMap<>();
        if (userIds.isEmpty()) {
            return tokens;
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO confirmable_notification_recipients "
                        + "(confirmable_notification_id, user_id, confirm_token, is_confirmed, "
                        + "resolved_first_reminder_minutes, resolved_second_reminder_minutes, created_at) VALUES ");
        Object[] args = new Object[userIds.size() * 5];
        int a = 0;
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,0,?,?,UTC_TIMESTAMP())");
            Long userId = userIds.get(i);
            String token = UUID.randomUUID().toString();
            tokens.put(userId, token);
            args[a++] = notificationId;
            args[a++] = userId;
            args[a++] = token;
            args[a++] = resolvedFirstReminderMinutes;
            args[a++] = resolvedSecondReminderMinutes;
        }
        jdbcTemplate.update(sql.toString(), args);
        return tokens;
    }

    /**
     * アプリ内通知（{@code notifications}）を 1 文の多値 INSERT で作成する。
     *
     * <p>{@code organization_id} は組織スコープのときだけ scope_id を入れる（それ以外は NULL）。
     * 戻り値は採番 id 付きの通知で、呼び出し側がコミット後の WebSocket / Push 配信
     * （{@code NotificationDispatchService#dispatchBatch}）に渡すためのもの。</p>
     *
     * @param notification        確認通知（source_id・scope・title 等の素材）
     * @param userIds             新規受信者（重複なし・最大 {@link #CHUNK_SIZE} 人）
     * @param appNotificationType 通知種別（例: {@code CONFIRMABLE_NOTIFICATION} / {@code RECRUITMENT_PENALTY_APPLIED}）
     * @return 作成した通知（採番 id 付き）
     */
    public List<NotificationEntity> insertAppNotifications(ConfirmableNotificationEntity notification,
            List<Long> userIds, String appNotificationType) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        NotificationScopeType scopeType = toNotificationScopeType(notification.getScopeType());
        NotificationPriority priority = notification.getPriority() == null
                ? NotificationPriority.NORMAL
                : NotificationPriority.valueOf(notification.getPriority().name());
        Long organizationId = notification.getScopeType() == ScopeType.ORGANIZATION ? notification.getScopeId() : null;
        String body = notification.getBody() != null ? notification.getBody() : "";

        StringBuilder sql = new StringBuilder(
                "INSERT INTO notifications (user_id, organization_id, notification_type, priority, title, body, "
                        + "source_type, source_id, scope_type, scope_id, action_url, actor_id, is_read, created_at) VALUES ");
        Object[] args = new Object[userIds.size() * 12];
        int a = 0;
        List<NotificationEntity> seeds = new ArrayList<>(userIds.size());
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?,?,?,?,?,?,0,UTC_TIMESTAMP())");
            args[a++] = userIds.get(i);
            args[a++] = organizationId;
            args[a++] = appNotificationType;
            args[a++] = priority.name();
            args[a++] = notification.getTitle();
            args[a++] = body;
            args[a++] = SOURCE_TYPE;
            args[a++] = notification.getId();
            args[a++] = scopeType.name();
            args[a++] = notification.getScopeId();
            args[a++] = notification.getActionUrl();
            args[a++] = notification.getCreatedByUserId();
            seeds.add(NotificationEntity.builder()
                    .userId(userIds.get(i))
                    .organizationId(organizationId)
                    .notificationType(appNotificationType)
                    .priority(priority)
                    .title(notification.getTitle())
                    .body(body)
                    .sourceType(SOURCE_TYPE)
                    .sourceId(notification.getId())
                    .scopeType(scopeType)
                    .scopeId(notification.getScopeId())
                    .actionUrl(notification.getActionUrl())
                    .actorId(notification.getCreatedByUserId())
                    .isRead(false)
                    .createdAt(now)
                    .build());
        }

        final String insertSql = sql.toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);

        List<Map<String, Object>> keyRows = keyHolder.getKeyList();
        if (keyRows.size() != seeds.size()) {
            // 採番 id が行数と合わない場合は id なしで返す（配信を止めない）。DB 行自体は作成済み。
            return seeds;
        }
        List<NotificationEntity> withId = new ArrayList<>(seeds.size());
        for (int i = 0; i < seeds.size(); i++) {
            Map<String, Object> row = keyRows.get(i);
            Object value = row.get("GENERATED_KEY");
            if (value == null && !row.isEmpty()) {
                value = row.values().iterator().next();
            }
            withId.add(value == null ? seeds.get(i)
                    : seeds.get(i).toBuilder().id(((Number) value).longValue()).build());
        }
        return withId;
    }

    /** 確認通知のスコープを F04.3 通知基盤のスコープへ変換する（PLATFORM は SYSTEM）。 */
    private static NotificationScopeType toNotificationScopeType(ScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PLATFORM -> NotificationScopeType.SYSTEM;
            case COMMITTEE -> NotificationScopeType.COMMITTEE;
        };
    }
}
