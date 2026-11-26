package com.mannschaft.app.contact;

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
 * 試練（CMP-260826-1457 W2・contact 系統）: 実 Flyway で旧スキーマ（V268.20261124173054 まで。本 migration は V269）に既存の招待を置き、
 * 全件を適用したあとの形と、既存トークンの失効（AC-x1 の実スキーマ・AC-A）を確かめる。
 *
 * <p>IT のスキーマは test プロファイルの {@code ddl-auto=create}（Entity 由来）で、migration の列・索引は
 * 存在しない。そのため fresh な MySQL に migration を流して観測する。</p>
 */
@EnabledIf("com.mannschaft.app.contact.ContactInviteTokenHashMigrationIT#isDockerAvailable")
@DisplayName("CMP-260826-1457 W2 contact: contact_invite_tokens の migration")
class ContactInviteTokenHashMigrationIT {

    static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Test
    @DisplayName("AC-x1・AC-A: 旧列 token が消え token_hash が NOT NULL+UNIQUE。既存行は残り全件が失効し、旧平文では照合できない")
    void 既存トークンは失効し行は残る() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("contact_invite_hash_migration")
                .withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCommand("--log_bin_trust_function_creators=1")) {
            mysql.start();
            migrate(mysql, "268.20261124173054");
            String legacyToken = "11111111-2222-3333-4444-555555555555";
            long id;
            try (Connection c = connect(mysql); Statement s = c.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS = 0");
                s.executeUpdate("INSERT INTO contact_invite_tokens (user_id, token, label, max_uses, used_count) "
                        + "VALUES (990001, '" + legacyToken + "', '旧', 5, 2)");
                s.executeUpdate("INSERT INTO contact_invite_tokens (user_id, token, revoked_at) "
                        + "VALUES (990001, '99999999-2222-3333-4444-555555555555', '2026-01-02 03:04:05')");
                try (ResultSet r = s.executeQuery("SELECT id FROM contact_invite_tokens WHERE token = '" + legacyToken + "'")) {
                    assertThat(r.next()).isTrue();
                    id = r.getLong(1);
                }
            }

            migrate(mysql, null);

            try (Connection c = connect(mysql); Statement s = c.createStatement()) {
                List<String> columns = new ArrayList<>();
                try (ResultSet r = s.executeQuery("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contact_invite_tokens'")) {
                    while (r.next()) columns.add(r.getString(1));
                }
                assertThat(columns).contains("token_hash").doesNotContain("token");
                try (ResultSet r = s.executeQuery("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contact_invite_tokens' "
                        + "AND COLUMN_NAME = 'token_hash'")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo("NO");
                }
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contact_invite_tokens' "
                        + "AND COLUMN_NAME = 'token_hash' AND NON_UNIQUE = 0")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getInt(1)).as("token_hash の UNIQUE 索引").isEqualTo(1);
                }
                try (ResultSet r = s.executeQuery("SELECT label, used_count, revoked_at, token_hash "
                        + "FROM contact_invite_tokens WHERE id = " + id)) {
                    assertThat(r.next()).as("行・id は残る").isTrue();
                    assertThat(r.getString("label")).isEqualTo("旧");
                    assertThat(r.getInt("used_count")).isEqualTo(2);
                    assertThat(r.getTimestamp("revoked_at")).as("未失効だった行は失効する").isNotNull();
                    assertThat(r.getString("token_hash")).matches("^[0-9a-f]{64}$")
                            .isNotEqualTo(sha256Hex(legacyToken));
                }
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM contact_invite_tokens WHERE revoked_at IS NULL")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getInt(1)).as("失効していない行が残らない").isZero();
                }
                try (ResultSet r = s.executeQuery("SELECT revoked_at FROM contact_invite_tokens WHERE id <> " + id)) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getTimestamp(1).toString()).as("既に失効していた行は日時を保つ")
                            .startsWith("2026-01-02 03:04:05");
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
