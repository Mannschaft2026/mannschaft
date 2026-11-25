package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * CMP-261002-1559: {@link ConfirmableRecipientBulkWriter} の単体テスト。
 *
 * <p>実 DB の SQL 文数・一意制約・ロールバックは {@code ConfirmableNotificationSyncSendBulkIT} が担う。
 * 本クラスは「1 文＝複数行」の SQL の形と、アプリ内通知の organization_id / scope / 採番 id の充填規則を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261002-1559 ConfirmableRecipientBulkWriter（単体）")
class ConfirmableRecipientBulkWriterTest {

    private static final Long NOTIFICATION_ID = 77L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ACTOR_ID = 5L;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ConfirmableRecipientBulkWriter writer;

    /** insertAppNotifications が prepareStatement に渡した SQL と setObject の引数。 */
    private String preparedSql;
    private final Map<Integer, Object> boundArgs = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        writer = new ConfirmableRecipientBulkWriter(jdbcTemplate);
        // update(PreparedStatementCreator, KeyHolder): creator を実行して SQL・引数を捕まえ、採番キーを 1001.. で返す。
        given(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).willAnswer(inv -> {
            PreparedStatementCreator creator = inv.getArgument(0);
            KeyHolder keyHolder = inv.getArgument(1);
            Connection connection = mock(Connection.class);
            PreparedStatement ps = mock(PreparedStatement.class);
            given(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
                    .willAnswer(c -> {
                        preparedSql = c.getArgument(0);
                        return ps;
                    });
            org.mockito.Mockito.doAnswer(c -> {
                boundArgs.put(c.getArgument(0), c.getArgument(1));
                return null;
            }).when(ps).setObject(org.mockito.ArgumentMatchers.anyInt(), any());
            creator.createPreparedStatement(connection);
            int rows = preparedSql.split("\\?,\\?,\\?,\\?,\\?,\\?,\\?,\\?,\\?,\\?,\\?,\\?,0,UTC_TIMESTAMP\\(\\)", -1).length - 1;
            for (int i = 0; i < rows; i++) {
                Map<String, Object> key = new HashMap<>();
                key.put("GENERATED_KEY", 1001L + i);
                keyHolder.getKeyList().add(key);
            }
            return rows;
        });
    }

    private static ConfirmableNotificationEntity notification(ScopeType scopeType) {
        return ConfirmableNotificationEntity.builder()
                .id(NOTIFICATION_ID)
                .scopeType(scopeType)
                .scopeId(SCOPE_ID)
                .title("タイトル")
                .body(null)
                .priority(ConfirmableNotificationPriority.HIGH)
                .actionUrl("/x")
                .createdBy(createdBy())
                .build();
    }

    private static UserEntity createdBy() {
        UserEntity user = mock(UserEntity.class);
        given(user.getId()).willReturn(ACTOR_ID);
        return user;
    }

    private static List<Long> users(int count) {
        return LongStream.rangeClosed(1L, count).boxed().toList();
    }

    @Test
    @DisplayName("受信者行: 500人でも INSERT は1文（多値）で、各人に確認トークンが払い出され、解決済みリマインド分数が全行に入る")
    void insertRecipients_500人は1文の多値INSERT() {
        List<Long> users = users(500);

        Map<Long, String> tokens = writer.insertRecipients(NOTIFICATION_ID, users, 60, 30);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).startsWith("INSERT INTO confirmable_notification_recipients");
        assertThat(sql.getValue().split("\\(\\?,\\?,\\?,0,\\?,\\?,UTC_TIMESTAMP\\(\\)\\)", -1).length - 1)
                .as("1 文に 500 行ぶんの VALUES タプル").isEqualTo(500);
        assertThat(args.getValue()).hasSize(500 * 5);
        assertThat(tokens).hasSize(500);
        assertThat(tokens.values()).doesNotHaveDuplicates();
        assertThat(args.getValue()[3]).isEqualTo(60);
        assertThat(args.getValue()[4]).isEqualTo(30);
    }

    @Test
    @DisplayName("アプリ内通知: 組織スコープでは organization_id に scope_id が入り、本文 NULL は空文字、採番 id が通知に載る")
    void insertAppNotifications_組織スコープはorganization_idを充填し採番idを返す() {
        List<NotificationEntity> created = writer.insertAppNotifications(
                notification(ScopeType.ORGANIZATION), List.of(11L, 12L), "CONFIRMABLE_NOTIFICATION");

        assertThat(preparedSql).startsWith("INSERT INTO notifications (user_id, organization_id,");
        // 1 行目: user_id, organization_id, type, priority, title, body, source_type, source_id, scope_type, scope_id, url, actor
        assertThat(boundArgs.get(1)).isEqualTo(11L);
        assertThat(boundArgs.get(2)).as("organization_id = 組織スコープの scope_id").isEqualTo(SCOPE_ID);
        assertThat(boundArgs.get(3)).isEqualTo("CONFIRMABLE_NOTIFICATION");
        assertThat(boundArgs.get(4)).isEqualTo("HIGH");
        assertThat(boundArgs.get(6)).as("body が NULL のときは空文字").isEqualTo("");
        assertThat(boundArgs.get(7)).isEqualTo("CONFIRMABLE_NOTIFICATION");
        assertThat(boundArgs.get(8)).isEqualTo(NOTIFICATION_ID);
        assertThat(boundArgs.get(9)).isEqualTo("ORGANIZATION");
        assertThat(boundArgs.get(12)).as("actor_id = 作成者").isEqualTo(ACTOR_ID);

        assertThat(created).extracting(NotificationEntity::getUserId).containsExactly(11L, 12L);
        assertThat(created).extracting(NotificationEntity::getId).containsExactly(1001L, 1002L);
        assertThat(created).extracting(NotificationEntity::getOrganizationId).containsOnly(SCOPE_ID);
        assertThat(created).extracting(NotificationEntity::getScopeType).containsOnly(NotificationScopeType.ORGANIZATION);
    }

    @Test
    @DisplayName("アプリ内通知: チームスコープでは organization_id は NULL、種別（RECRUITMENT_PENALTY_APPLIED）は引数で決まる")
    void insertAppNotifications_チームスコープはorganization_idがNULLで種別は引数() {
        List<NotificationEntity> created = writer.insertAppNotifications(
                notification(ScopeType.TEAM), List.of(11L), "RECRUITMENT_PENALTY_APPLIED");

        assertThat(boundArgs.get(2)).as("organization_id は NULL").isNull();
        assertThat(boundArgs.get(3)).isEqualTo("RECRUITMENT_PENALTY_APPLIED");
        assertThat(boundArgs.get(9)).isEqualTo("TEAM");
        assertThat(created.get(0).getOrganizationId()).isNull();
        assertThat(created.get(0).getNotificationType()).isEqualTo("RECRUITMENT_PENALTY_APPLIED");
    }

    @Test
    @DisplayName("アプリ内通知: PLATFORM スコープは通知基盤の SYSTEM スコープへ変換して保存する")
    void insertAppNotifications_PLATFORMはSYSTEMへ変換() {
        List<NotificationEntity> created = writer.insertAppNotifications(
                notification(ScopeType.PLATFORM), List.of(11L), "CONFIRMABLE_NOTIFICATION");

        assertThat(boundArgs.get(9)).isEqualTo("SYSTEM");
        assertThat(created.get(0).getScopeType()).isEqualTo(NotificationScopeType.SYSTEM);
    }

    @Test
    @DisplayName("アプリ内通知: 空の受信者では SQL を発行せず空リストを返す")
    void insertAppNotifications_空なら何も発行しない() {
        List<NotificationEntity> created = new ArrayList<>(
                writer.insertAppNotifications(notification(ScopeType.TEAM), List.of(), "CONFIRMABLE_NOTIFICATION"));

        assertThat(created).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }
}
