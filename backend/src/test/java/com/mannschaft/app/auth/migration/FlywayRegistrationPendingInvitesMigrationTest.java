package com.mannschaft.app.auth.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261107-1833 AC12: {@code registration_pending_invites} の DDL を<b>実 Flyway migration</b>で検証する。
 *
 * <p>IT のスキーマは test プロファイルの {@code ddl-auto=create}（Entity 由来）で、migration に書いた索引・FK・CHECK・
 * 照合順序は存在しない。そのため fresh な MySQL に全 migration を流して確かめる。版番号はマージ時に付け替わりうるため、
 * 対象は説明名 {@code create_registration_pending_invites} で探す。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.auth.migration.FlywayRegistrationPendingInvitesMigrationTest#isDockerAvailable")
@DisplayName("CMP-261107-1833 AC12 registration_pending_invites の migration")
class FlywayRegistrationPendingInvitesMigrationTest {

    private static final String TABLE = "registration_pending_invites";
    private static final String SCRIPT_SUFFIX = "__create_registration_pending_invites.sql";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_pending_invites")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return "true".equalsIgnoreCase(System.getenv("CI")) || DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return "true".equalsIgnoreCase(System.getenv("CI"));
        }
    }

    @BeforeAll
    void migrate() {
        MYSQL.start();
        flyway().load().migrate();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    @Test
    @DisplayName("AC12: create_registration_pending_invites の migration が1本あり適用済み、再実行で保留なし")
    void migrationExistsAndIsApplied() {
        Flyway again = flyway().load();
        List<MigrationInfo> scripts = Arrays.stream(again.info().all())
                .filter(i -> i.getScript().endsWith(SCRIPT_SUFFIX))
                .toList();
        assertThat(scripts).as(SCRIPT_SUFFIX + " の migration").hasSize(1);
        assertThat(scripts.get(0).getState().isApplied()).isTrue();
        assertThat(scripts.get(0).getVersion().getVersion()).as("V{major}.{yyyyMMddHHmmss}").matches("\\d+\\.\\d{14}");
        assertThat(again.migrate().migrationsExecuted).isZero();
    }

    @Test
    @DisplayName("AC12: 列の型・NULL 可否（id BINARY(16)・user_id BIGINT UNSIGNED・token_hash CHAR(64)・accept_attempt_id BINARY(16) ほか）")
    void columns() throws Exception {
        Map<String, Map<String, String>> columns = new HashMap<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE + "'")) {
            while (rs.next()) {
                columns.put(rs.getString(1), Map.of("type", rs.getString(2).toLowerCase(), "nullable", rs.getString(3)));
            }
        }
        assertThat(columns).as(TABLE + " の列").containsKeys("id", "user_id", "token_hash", "scope_type", "scope_id",
                "status", "failure_code", "accept_attempt_id", "accepting_started_at", "reissue_requested_at", "version",
                "created_at", "updated_at");
        assertThat(columns.get("id").get("type")).isEqualTo("binary(16)");
        assertThat(columns.get("user_id").get("type")).isEqualTo("bigint unsigned");
        assertThat(columns.get("token_hash").get("type")).isEqualTo("char(64)");
        assertThat(columns.get("scope_type").get("type")).isEqualTo("varchar(20)");
        assertThat(columns.get("scope_id").get("type")).isEqualTo("bigint unsigned");
        assertThat(columns.get("status").get("type")).isEqualTo("varchar(20)");
        assertThat(columns.get("failure_code").get("type")).isEqualTo("varchar(20)");
        assertThat(columns.get("accept_attempt_id").get("type")).isEqualTo("binary(16)");
        assertThat(columns.get("accepting_started_at").get("type")).isEqualTo("datetime(6)");
        assertThat(columns.get("reissue_requested_at").get("type")).isEqualTo("datetime(6)");
        for (String notNull : List.of("id", "user_id", "token_hash", "scope_type", "scope_id", "status")) {
            assertThat(columns.get(notNull).get("nullable")).as(notNull + " は NOT NULL").isEqualTo("NO");
        }
        for (String nullable : List.of("failure_code", "accept_attempt_id", "accepting_started_at", "reissue_requested_at")) {
            assertThat(columns.get(nullable).get("nullable")).as(nullable + " は NULL 可").isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("AC12: UNIQUE(user_id)（1利用者1行）")
    void uniqueUserId() throws Exception {
        List<String> uniques = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
                     + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE
                     + "' AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME")) {
            while (rs.next()) {
                uniques.add(rs.getString(2));
            }
        }
        assertThat(uniques).contains("user_id");
    }

    @Test
    @DisplayName("AC12: FK は user_id→users の ON DELETE CASCADE（同ドメイン）だけ。招待表・スコープへの FK なし")
    void foreignKeys() throws Exception {
        List<String> fks = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, r.DELETE_RULE "
                     + "FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                     + "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                     + "WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = '" + TABLE + "' AND k.REFERENCED_TABLE_NAME IS NOT NULL")) {
            while (rs.next()) {
                fks.add(rs.getString(1) + "->" + rs.getString(2) + ":" + rs.getString(3));
            }
        }
        assertThat(fks).containsExactly("user_id->users:CASCADE");
    }

    @Test
    @DisplayName("AC12: 照合順序は表・文字列列とも utf8mb4_0900_ai_ci")
    void collation() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE + "'")) {
                assertThat(rs.next()).as(TABLE + " が存在する").isTrue();
                assertThat(rs.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
            }
            try (ResultSet rs = s.executeQuery("SELECT COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE + "' AND COLLATION_NAME IS NOT NULL")) {
                while (rs.next()) {
                    assertThat(rs.getString(2)).as(rs.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
                }
            }
        }
    }

    @Test
    @DisplayName("AC12: CHECK(status) は設計の9状態を受け、それ以外を拒否する。同一 user_id の2行目は一意制約で拒否、users 削除で連鎖削除")
    void checkUniqueAndCascade() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users (email, last_name, first_name, display_name, status, is_searchable, "
                    + "handle_searchable, contact_approval_required, online_visibility, dm_receive_from, encryption_key_version, "
                    + "locale, timezone, reporting_restricted, follow_list_visibility, care_notification_enabled, offline_only, "
                    + "created_at, updated_at) VALUES ('pending-invite-migration@example.com', '移行', '保留', '保留試練', "
                    + "'PENDING_VERIFICATION', 1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())");
            long userId;
            try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE email = 'pending-invite-migration@example.com'")) {
                rs.next();
                userId = rs.getLong(1);
            }
            assertThatThrownBy(() -> s.executeUpdate(row(userId, "BOGUS"))).as("不正な status").isInstanceOf(SQLException.class);
            for (String status : List.of("PENDING", "ACCEPTING", "JOINED", "EXPIRED", "UNAVAILABLE", "DECLINED",
                    "REISSUE_REQUESTED", "DISMISSED", "DISCARDED")) {
                assertThat(s.executeUpdate(row(userId, status))).as(status).isEqualTo(1);
                s.executeUpdate("DELETE FROM " + TABLE + " WHERE user_id = " + userId);
            }
            assertThat(s.executeUpdate(row(userId, "PENDING"))).isEqualTo(1);
            assertThatThrownBy(() -> s.executeUpdate(row(userId, "PENDING"))).as("UNIQUE(user_id)")
                    .isInstanceOf(SQLException.class);
            s.executeUpdate("DELETE FROM users WHERE id = " + userId);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE user_id = " + userId)) {
                rs.next();
                assertThat(rs.getLong(1)).as("users 物理削除で CASCADE").isZero();
            }
        }
    }

    // ------------------------------------------------------------------
    // CMP-261107-1833 PR3（再招待の受け手側）: alter_registration_pending_invites_reissue
    // ------------------------------------------------------------------

    private static final String REISSUE_SCRIPT_SUFFIX = "__alter_registration_pending_invites_reissue.sql";

    @Test
    @DisplayName("PR3 A2: alter_registration_pending_invites_reissue の migration が1本あり適用済み、create より後の版（V{major}.{yyyyMMddHHmmss}）")
    void reissueMigrationExistsAndIsApplied() {
        Flyway again = flyway().load();
        List<MigrationInfo> scripts = Arrays.stream(again.info().all())
                .filter(i -> i.getScript().endsWith(REISSUE_SCRIPT_SUFFIX))
                .toList();
        assertThat(scripts).as(REISSUE_SCRIPT_SUFFIX + " の migration").hasSize(1);
        assertThat(scripts.get(0).getState().isApplied()).isTrue();
        assertThat(scripts.get(0).getVersion().getVersion()).as("V{major}.{yyyyMMddHHmmss}").matches("\\d+\\.\\d{14}");
        MigrationInfo create = Arrays.stream(again.info().all())
                .filter(i -> i.getScript().endsWith(SCRIPT_SUFFIX)).findFirst().orElseThrow();
        assertThat(scripts.get(0).getVersion().compareTo(create.getVersion())).as("create より後").isPositive();
    }

    @Test
    @DisplayName("PR3 A2: CHECK(status) が REISSUE_DECLINED を含む 10 状態を受け、それ以外を拒否する")
    void checkAcceptsReissueDeclined() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            long userId = insertUser(s, "pending-invite-reissue-check@example.com");
            for (String status : List.of("PENDING", "ACCEPTING", "JOINED", "EXPIRED", "UNAVAILABLE", "DECLINED",
                    "REISSUE_REQUESTED", "REISSUE_DECLINED", "DISMISSED", "DISCARDED")) {
                assertThat(s.executeUpdate(row(userId, status))).as(status).isEqualTo(1);
                s.executeUpdate("DELETE FROM " + TABLE + " WHERE user_id = " + userId);
            }
            assertThatThrownBy(() -> s.executeUpdate(row(userId, "REISSUE_BOGUS"))).as("不正な status")
                    .isInstanceOf(SQLException.class);
            s.executeUpdate("DELETE FROM users WHERE id = " + userId);
        }
    }

    @Test
    @DisplayName("PR3 A2: 索引 idx_rpi_scope_status(scope_type, scope_id, status, reissue_requested_at)（一覧・件数の述語用）")
    void scopeStatusIndex() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE + "' AND INDEX_NAME = 'idx_rpi_scope_status'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("scope_type,scope_id,status,reissue_requested_at");
        }
    }

    @Test
    @DisplayName("PR3 L8: users を物理削除すると REISSUE_DECLINED の保留も CASCADE で消える")
    void cascadeDeletesReissueDeclined() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            long userId = insertUser(s, "pending-invite-reissue-cascade@example.com");
            assertThat(s.executeUpdate(row(userId, "REISSUE_DECLINED"))).isEqualTo(1);
            s.executeUpdate("DELETE FROM users WHERE id = " + userId);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE user_id = " + userId)) {
                rs.next();
                assertThat(rs.getLong(1)).as("users 物理削除で REISSUE_DECLINED も CASCADE").isZero();
            }
        }
    }

    // ------------------------------------------------------------------
    // CMP-261107-1833 PR3 検分 P2-2・P2: 窓と 7 日の境界を、本番と同じ SQL 断片で実 DB 上に固定する
    // （現在時刻だけを固定値に差し替える。窓は GET と有効化で同じ断片を使う）
    // ------------------------------------------------------------------

    private static final String NOW = "2026-11-24 00:01:00.000000";

    private static boolean evalFragment(Statement s, String fragment, String columns) throws SQLException {
        assertThat(fragment).as("断片は DB の現在時刻 UTC_TIMESTAMP(6) を使う").contains("UTC_TIMESTAMP(6)");
        String sql = "SELECT " + fragment.replace("UTC_TIMESTAMP(6)", "TIMESTAMP('" + NOW + "')") + " FROM (SELECT "
                + columns + ") t";
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static boolean stagingOpen(Statement s, String createdAt) throws SQLException {
        return evalFragment(s, com.mannschaft.app.role.dto.InviteValidity.STAGING_OPEN_SQL,
                "TIMESTAMP('" + createdAt + "') AS revoked_at, TIMESTAMP('" + createdAt + "') AS created_at");
    }

    @Test
    @DisplayName("PR3 検分 P2-2: 仮発行の窓は、作成から 59.5 秒・ちょうど 60 秒は処理中、60.5 秒は窓の外（GET と有効化で同じ断片・6 桁精度）")
    void stagingWindowBoundary() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            assertThat(stagingOpen(s, "2026-11-24 00:00:00.500000")).as("59.5 秒").isTrue();
            assertThat(stagingOpen(s, "2026-11-24 00:00:00.000000")).as("ちょうど 60 秒").isTrue();
            assertThat(stagingOpen(s, "2026-11-23 23:59:59.500000")).as("60.5 秒").isFalse();
            assertThat(evalFragment(s, com.mannschaft.app.role.dto.InviteValidity.STAGING_OPEN_SQL,
                    "TIMESTAMP('2026-11-24 00:00:30.000000') AS revoked_at, TIMESTAMP('2026-11-24 00:00:00.000000') AS created_at"))
                    .as("通常の失効（revoked_at ≠ created_at）は窓に入らない").isFalse();
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 24, 0, 1, 0);
        assertThat(com.mannschaft.app.role.dto.InviteValidity.evaluate(now.minusSeconds(60), now.minusSeconds(60), null, 1, 0, now))
                .as("Java 側の判定もちょうど 60 秒は処理中").isEqualTo(com.mannschaft.app.role.dto.InviteValidity.STAGING);
        java.time.LocalDateTime past = now.minusSeconds(60).minusNanos(500_000_000);
        assertThat(com.mannschaft.app.role.dto.InviteValidity.evaluate(past, past, null, 1, 0, now))
                .as("Java 側の判定も 60.5 秒は窓の外").isEqualTo(com.mannschaft.app.role.dto.InviteValidity.REVOKED);
    }

    private static int activateAt(Statement s, String now, long id, long target) throws SQLException {
        String sql = com.mannschaft.app.role.repository.InviteTokenRepositoryCustom.ACTIVATE_STAGED_SQL
                .replace(":id", Long.toString(id)).replace(":targetUserId", Long.toString(target))
                .replace("UTC_TIMESTAMP(6)", "TIMESTAMP('" + now + "')");
        return s.executeUpdate(sql);
    }

    private static boolean stagingOpenAt(Statement s, String now, long id) throws SQLException {
        String fragment = com.mannschaft.app.role.dto.InviteValidity.STAGING_OPEN_SQL
                .replace("UTC_TIMESTAMP(6)", "TIMESTAMP('" + now + "')");
        try (ResultSet rs = s.executeQuery("SELECT " + fragment + " FROM invite_tokens WHERE id = " + id)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    @Test
    @DisplayName("PR3 再検分: 不変条件。参照が窓の外と判定した時刻以降、有効化の UPDATE（同じ述語）は 0 行。窓の内でだけ 1 行になる")
    void activationNeverSucceedsAfterGetJudgedOutside() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            // 本番の invite_tokens を同名の一時表で覆い、有効化の本番 SQL を固定時刻で評価する（この接続の中だけ）
            s.execute("CREATE TEMPORARY TABLE invite_tokens (id BIGINT PRIMARY KEY, target_user_id BIGINT, "
                    + "used_count INT NOT NULL, revoked_at DATETIME(6), created_at DATETIME(6) NOT NULL)");
            try {
                String created = "2026-11-24 00:00:00.000000";
                s.executeUpdate("INSERT INTO invite_tokens VALUES (1, 7, 0, '" + created + "', '" + created + "')");

                String getAt = "2026-11-24 00:01:00.500000"; // 作成から 60.5 秒: 参照は窓の外（EXPIRED）と判定する
                assertThat(stagingOpenAt(s, getAt, 1)).isFalse();
                assertThat(activateAt(s, getAt, 1, 7)).as("参照と同時刻の有効化は 0 行").isZero();
                assertThat(activateAt(s, "2026-11-24 00:01:01.000000", 1, 7)).as("参照より後の有効化も 0 行").isZero();

                assertThat(stagingOpenAt(s, "2026-11-24 00:01:00.000000", 1)).as("ちょうど 60 秒は窓の内").isTrue();
                assertThat(activateAt(s, "2026-11-24 00:01:00.000000", 1, 7)).as("窓の内でだけ 1 行").isEqualTo(1);
                assertThat(activateAt(s, "2026-11-24 00:01:00.000000", 1, 7)).as("有効化済みは 0 行").isZero();
            } finally {
                s.execute("DROP TEMPORARY TABLE invite_tokens");
            }
        }
    }

    @Test
    @DisplayName("PR3 検分 P2: 再依頼の通知は、前回からちょうど 7 日なら出さず、7 日と 0.5 秒なら出す。未依頼（NULL）は即時")
    void reissueCooldownBoundary() throws Exception {
        String fragment = com.mannschaft.app.auth.repository.RegistrationPendingInviteRepository.REISSUE_COOLDOWN_ELAPSED_SQL;
        try (Connection c = connection(); Statement s = c.createStatement()) {
            assertThat(evalFragment(s, fragment, "TIMESTAMP('2026-11-17 00:01:00.000000') AS reissue_requested_at"))
                    .as("ちょうど 7 日").isFalse();
            assertThat(evalFragment(s, fragment, "TIMESTAMP('2026-11-17 00:00:59.500000') AS reissue_requested_at"))
                    .as("7 日と 0.5 秒").isTrue();
            assertThat(evalFragment(s, fragment, "CAST(NULL AS DATETIME(6)) AS reissue_requested_at"))
                    .as("未依頼").isTrue();
        }
    }

    private static long insertUser(Statement s, String email) throws SQLException {
        s.executeUpdate("INSERT INTO users (email, last_name, first_name, display_name, status, is_searchable, "
                + "handle_searchable, contact_approval_required, online_visibility, dm_receive_from, encryption_key_version, "
                + "locale, timezone, reporting_restricted, follow_list_visibility, care_notification_enabled, offline_only, "
                + "created_at, updated_at) VALUES ('" + email + "', '移行', '再招待', '再招待試練', "
                + "'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())");
        try (ResultSet rs = s.executeQuery("SELECT id FROM users WHERE email = '" + email + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String row(long userId, String status) {
        return "INSERT INTO " + TABLE + " (id, user_id, token_hash, scope_type, scope_id, status, version, created_at, updated_at) "
                + "VALUES (UNHEX(REPLACE(UUID(),'-','')), " + userId + ", REPEAT('a', 64), 'TEAM', 1, '" + status
                + "', 0, NOW(6), NOW(6))";
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
