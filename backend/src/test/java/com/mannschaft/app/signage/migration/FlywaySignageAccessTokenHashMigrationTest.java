package com.mannschaft.app.signage.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練（CMP-260826-1457 W2・signage 系統）: {@code signage_access_tokens.token} をハッシュ列へ移す migration を、
 * 既存データを持つ DB へ実 Flyway で適用して検証する。
 *
 * <p>AC-x1: 平文列 token が無く、token_hash が NOT NULL かつ UNIQUE（旧索引も無い）。
 * AC-A: 移行前に有効だったトークンは、移行後は失効し（is_active=0）、行・ID・画面との結び付きは残る。
 * 移行前の平文の SHA-256 は保存されない（旧トークンを生かす互換照合を作らない）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.signage.migration.FlywaySignageAccessTokenHashMigrationTest#isDockerAvailable")
@DisplayName("Flyway signage_access_tokens の平文列廃止とハッシュ列への移行（全件失効）")
class FlywaySignageAccessTokenHashMigrationTest {

    private static final String PRE_TARGET = "267.20261124005850";
    private static final String OLD_PLAINTEXT = "11111111-2222-4333-8444-555555555555";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_signage_token_hash")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
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
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target == null ? MigrationVersion.LATEST : MigrationVersion.fromVersion(target))
                .load();
    }

    @Test
    @DisplayName("既存の有効トークンは移行後に失効し行が残る。token 列と旧索引は無く、token_hash は NOT NULL かつ UNIQUE")
    void 既存トークンは失効しスキーマはハッシュ列だけになる() throws Exception {
        flyway(PRE_TARGET).migrate();
        long tokenId;
        long screenId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            s.execute("INSERT INTO signage_screens (scope_type, scope_id, name, layout, created_by, created_at, updated_at) "
                    + "VALUES ('TEAM', 1, '移行前画面', 'LANDSCAPE', 1, NOW(), NOW())");
            try (ResultSet rs = s.executeQuery("SELECT LAST_INSERT_ID()")) {
                rs.next();
                screenId = rs.getLong(1);
            }
            s.execute("INSERT INTO signage_access_tokens (screen_id, token, name, is_active, created_by, created_at, updated_at) "
                    + "VALUES (" + screenId + ", '" + OLD_PLAINTEXT + "', '移行前端末', 1, 1, NOW(), NOW())");
            try (ResultSet rs = s.executeQuery("SELECT LAST_INSERT_ID()")) {
                rs.next();
                tokenId = rs.getLong(1);
            }
            s.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        flyway(null).migrate();

        try (Connection c = conn(); Statement s = c.createStatement()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'signage_access_tokens'")) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            assertThat(columns).contains("token_hash").doesNotContain("token");

            try (ResultSet rs = s.executeQuery("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'signage_access_tokens' "
                    + "AND COLUMN_NAME = 'token_hash'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("NO");
            }

            // token_hash を先頭列に持つ一意索引がちょうど1つあり、旧 token 列の索引は残っていない
            List<String> uniqueIndexColumns = new ArrayList<>();
            List<String> indexedColumns = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'signage_access_tokens' AND INDEX_NAME <> 'PRIMARY'")) {
                while (rs.next()) {
                    indexedColumns.add(rs.getString("COLUMN_NAME"));
                    if (rs.getInt("NON_UNIQUE") == 0) {
                        uniqueIndexColumns.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            assertThat(uniqueIndexColumns).containsExactly("token_hash");
            assertThat(indexedColumns).doesNotContain("token");

            // AC-A: 行・ID・画面との結び付きは残り、失効している。旧平文のハッシュは保存されない
            try (ResultSet rs = s.executeQuery("SELECT screen_id, is_active, token_hash FROM signage_access_tokens "
                    + "WHERE id = " + tokenId)) {
                assertThat(rs.next()).as("移行前の行は削除されない").isTrue();
                assertThat(rs.getLong("screen_id")).isEqualTo(screenId);
                assertThat(rs.getInt("is_active")).as("移行前に有効だったトークンは失効する").isZero();
                assertThat(rs.getString("token_hash")).matches("^[0-9a-f]{64}$");
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM signage_access_tokens WHERE token_hash = SHA2('"
                    + OLD_PLAINTEXT + "', 256)")) {
                rs.next();
                assertThat(rs.getLong(1)).as("旧平文のハッシュで照合できる状態を残さない").isZero();
            }
        }
    }
}
