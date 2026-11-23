package com.mannschaft.app.common.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261107-1833 V266（invite_token_claims の付与印・旧データの印）の移行を、適用前に既存行を置いた実 MySQL で検証する。
 *
 * <p>V266 の直前まで migrate して GRANTED・CLAIMED・RELEASED の旧行を置き、V266 を適用して
 * GRANTED には付与印、CLAIMED には「付与されたか不明」の旧データの印が付き、RELEASED には何も付かないことを確かめる。
 * あわせて CHECK 制約と、手動ロールバック手順（db/rollback）で列と制約が消えることを確かめる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("com.mannschaft.app.common.migration.FlywayInviteTokenClaimsGrantMarkMigrationTest#isDockerAvailable")
@DisplayName("Flyway V266 invite_token_claims の付与印・旧データの印の移行")
class FlywayInviteTokenClaimsGrantMarkMigrationTest {

    private static final String BEFORE_V266 = "265.20261122053747";
    private static final String ROLLBACK = "db/rollback/V266.20261122100130_rollback.sql";
    private static final String GRANTED_ID = "0190000000007000800000000000a001";
    private static final String CLAIMED_ID = "0190000000007000800000000000a002";
    private static final String RELEASED_ID = "0190000000007000800000000000a003";
    private static final Timestamp GRANTED_UPDATED_AT = Timestamp.valueOf("2026-11-01 09:00:00.123456");

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_claims_grant_mark")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return "true".equalsIgnoreCase(System.getenv("CI"))
                    || DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return "true".equalsIgnoreCase(System.getenv("CI"));
        }
    }

    @BeforeAll
    void migrateWithLegacyRows() throws Exception {
        MYSQL.start();
        flyway(BEFORE_V266).migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // 招待行は検証の対象外なので、FK を外して claim だけを置く（V266 前の版が残した行の再現）。
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            insert(s, GRANTED_ID, "GRANTED", "'" + GRANTED_UPDATED_AT + "'");
            insert(s, CLAIMED_ID, "CLAIMED", "UTC_TIMESTAMP(6)");
            insert(s, RELEASED_ID, "RELEASED", "UTC_TIMESTAMP(6)");
            s.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        flyway(null).migrate();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    @Test
    @Order(1)
    @DisplayName("GRANTED は付与印＝updated_at、旧 CLAIMED は旧データの印（付与されたか不明）、RELEASED は印なし")
    void 既存行の印() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = row(s, GRANTED_ID)) {
                assertThat(rs.getTimestamp("grant_committed_at")).isEqualTo(GRANTED_UPDATED_AT);
                assertThat(rs.getTimestamp("legacy_grant_unknown_at")).isNull();
            }
            try (ResultSet rs = row(s, CLAIMED_ID)) {
                assertThat(rs.getTimestamp("grant_committed_at")).as("旧 CLAIMED に付与印は補わない").isNull();
                assertThat(rs.getTimestamp("legacy_grant_unknown_at")).as("旧 CLAIMED は付与されたか不明").isNotNull();
            }
            try (ResultSet rs = row(s, RELEASED_ID)) {
                assertThat(rs.getTimestamp("grant_committed_at")).isNull();
                assertThat(rs.getTimestamp("legacy_grant_unknown_at")).isNull();
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("CHECK chk_itc_grant_committed: RELEASED に付与印は立てられない")
    void CHECK制約() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE invite_token_claims SET grant_committed_at = UTC_TIMESTAMP(6) "
                    + "WHERE id = UNHEX('" + RELEASED_ID + "')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_itc_grant_committed");
        }
    }

    @Test
    @Order(3)
    @DisplayName("手動ロールバック手順で付与印・旧データの印の列と CHECK が消え、既存行は残る")
    void ロールバック() throws Exception {
        List<String> statements = rollbackStatements();
        assertThat(statements).hasSize(2);
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'invite_token_claims' AND COLUMN_NAME IN ('grant_committed_at', 'legacy_grant_unknown_at')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_itc_grant_committed'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM invite_token_claims")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(3);
            }
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void insert(Statement s, String hexId, String status, String updatedAt) throws SQLException {
        s.executeUpdate("INSERT INTO invite_token_claims (id, token_id, user_id, status, created_at, updated_at) VALUES "
                + "(UNHEX('" + hexId + "'), 999999, 888888, '" + status + "', UTC_TIMESTAMP(6), " + updatedAt + ")");
    }

    private static ResultSet row(Statement s, String hexId) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT grant_committed_at, legacy_grant_unknown_at FROM invite_token_claims "
                + "WHERE id = UNHEX('" + hexId + "')");
        assertThat(rs.next()).isTrue();
        return rs;
    }

    /** ロールバック手順ファイルの「-- ALTER ...」行を実行文として取り出す（手動実行用テンプレートの正しさを確かめる）。 */
    private static List<String> rollbackStatements() throws IOException {
        try (InputStream in = FlywayInviteTokenClaimsGrantMarkMigrationTest.class.getClassLoader()
                .getResourceAsStream(ROLLBACK)) {
            assertThat(in).as(ROLLBACK).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith("-- ALTER "))
                    .map(line -> line.substring(3).replaceAll(";\\s*$", ""))
                    .toList();
        }
    }
}
