package com.mannschaft.app.event;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練（CMP-260826-1457 W2・event 系統）: 実 Flyway で旧スキーマ（V267 まで）に既存の招待を置き、
 * 全件を適用したあとの形と、既存トークンの失効（AC-x1 の実スキーマ・AC-A）を確かめる。
 */
@EnabledIf("com.mannschaft.app.event.EventGuestInviteTokenHashMigrationIT#isDockerAvailable")
@DisplayName("CMP-260826-1457 W2 event: event_guest_invite_tokens の migration")
class EventGuestInviteTokenHashMigrationIT {

    static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Test
    @DisplayName("AC-x1・AC-A: 旧列 token が消え token_hash が NOT NULL+UNIQUE。既存行は残り全件が無効化され、旧平文では照合できない")
    void 既存トークンは失効し行は残る() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("event_guest_invite_hash_migration")
                .withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCommand("--log_bin_trust_function_creators=1")) {
            mysql.start();
            migrate(mysql, "267.20261124005850");
            String legacyToken = "11111111-2222-3333-4444-555555555555";
            long id;
            try (Connection c = connect(mysql); Statement s = c.createStatement()) {
                // 試験用に存在しない event_id の行を置くため、同一ドメイン内 FK だけ外す（migration の対象は token 列のみ）
                s.execute("ALTER TABLE event_guest_invite_tokens DROP FOREIGN KEY fk_event_guest_invite_tokens_event");
                s.executeUpdate("INSERT INTO event_guest_invite_tokens (event_id, token, label, max_uses, used_count, is_active) "
                        + "VALUES (990001, '" + legacyToken + "', '旧', 5, 2, TRUE)");
                s.executeUpdate("INSERT INTO event_guest_invite_tokens (event_id, token, is_active) "
                        + "VALUES (990001, '99999999-2222-3333-4444-555555555555', FALSE)");
                try (ResultSet r = s.executeQuery("SELECT id FROM event_guest_invite_tokens WHERE token = '" + legacyToken + "'")) {
                    assertThat(r.next()).isTrue();
                    id = r.getLong(1);
                }
            }

            migrate(mysql, null);

            try (Connection c = connect(mysql); Statement s = c.createStatement()) {
                List<String> columns = new ArrayList<>();
                try (ResultSet r = s.executeQuery("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_guest_invite_tokens'")) {
                    while (r.next()) columns.add(r.getString(1));
                }
                assertThat(columns).contains("token_hash").doesNotContain("token");
                try (ResultSet r = s.executeQuery("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_guest_invite_tokens' "
                        + "AND COLUMN_NAME = 'token_hash'")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo("NO");
                }
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_guest_invite_tokens' "
                        + "AND COLUMN_NAME = 'token_hash' AND NON_UNIQUE = 0")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getInt(1)).as("token_hash の UNIQUE 索引").isEqualTo(1);
                }
                try (ResultSet r = s.executeQuery("SELECT label, used_count, is_active, token_hash "
                        + "FROM event_guest_invite_tokens WHERE id = " + id)) {
                    assertThat(r.next()).as("行・id は残る").isTrue();
                    assertThat(r.getString("label")).isEqualTo("旧");
                    assertThat(r.getInt("used_count")).isEqualTo(2);
                    assertThat(r.getBoolean("is_active")).as("有効だった行は無効化される").isFalse();
                    assertThat(r.getString("token_hash")).matches("^[0-9a-f]{64}$")
                            .isNotEqualTo(sha256Hex(legacyToken));
                }
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM event_guest_invite_tokens WHERE is_active = TRUE")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getInt(1)).as("有効な行が残らない").isZero();
                }
            }
        }
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static Connection connect(MySQLContainer<?> mysql) throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void migrate(MySQLContainer<?> mysql, String target) {
        var config = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").outOfOrder(false);
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }
}
