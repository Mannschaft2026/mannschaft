package com.mannschaft.app.effortrecord.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F06.4.1 PR-3a の Flyway 実 MySQL 試験（試練。CMP-261115-2145）。AC-57（E15）の PR-3a 分。
 *
 * <p>PR-3a の migration 直前まで適用して既存データ（users / teams / memberships の在籍行と終了行）を投入し、全量適用後に
 * (1) 既存データ無傷・memberships 既存行の disposition は NULL、(2) 新2表と新列の照合順序 utf8mb4_0900_ai_ci、
 * (3) CHECK 制約の拒否（effort_team_shares の state・版・加入期間 ID、timeline_effort_sources の版、memberships の disposition）、
 * (4) FK は timeline_effort_sources→timeline_posts の1本だけ（クロスドメイン FK なし）、(5) チーム側の表は組織列を持たず
 * 経路キーは team_id、(6) 設計の索引が EXPLAIN で使われる、(7) 再実行で保留 migration なし、を実測する。
 * 主キーの UUIDv7 は Entity の採番で決まるため Spring 側の {@code EffortShareQualificationBoundaryIT} で検査する。</p>
 *
 * <p>migration の版番号はマージ時に付け替わりうるため、対象は説明名 {@code create_effort_team_shares_and_membership_disposition} で探す。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.effortrecord.migration.FlywayEffortTeamSharesMigrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3a migration の実 MySQL 試験（AC-57）")
class FlywayEffortTeamSharesMigrationTest {

    private static final String SCRIPT_SUFFIX = "__create_effort_team_shares_and_membership_disposition.sql";
    private static final List<String> NEW_TABLES = List.of("effort_team_shares", "timeline_effort_sources");

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_effort_pr3a_migration")
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

    private List<String> checksumsBefore;
    private List<String> membershipsBefore;

    @BeforeAll
    void 直前版まで適用して既存データを入れてから全量適用する() throws Exception {
        MYSQL.start();
        flyway().target(versionBeforeTarget()).load().migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            for (String mail : List.of("pr3a-active@example.com", "pr3a-left@example.com")) {
                s.executeUpdate("INSERT INTO users (email, last_name, first_name, display_name, status, is_searchable, "
                        + "handle_searchable, contact_approval_required, online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, care_notification_enabled, offline_only, "
                        + "created_at, updated_at) VALUES ('" + mail + "', '移行', '既存', '既存利用者', 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())");
            }
            s.executeUpdate("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                    + "VALUES ('既存チーム', 'PUBLIC', 1, 0, 0, 'effort-pr3a-team', NOW(), NOW())");
            s.executeUpdate("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                    + "SELECT u.id, 'TEAM', t.id, 'MEMBER', NOW(), NOW(), NOW() FROM users u, teams t "
                    + "WHERE u.email = 'pr3a-active@example.com' AND t.slug = 'effort-pr3a-team'");
            s.executeUpdate("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, left_at, leave_reason, "
                    + "left_trigger, created_at, updated_at) "
                    + "SELECT u.id, 'TEAM', t.id, 'MEMBER', NOW(), NOW(), 'SELF', 'SELF', NOW(), NOW() FROM users u, teams t "
                    + "WHERE u.email = 'pr3a-left@example.com' AND t.slug = 'effort-pr3a-team'");
        }
        checksumsBefore = checksums();
        membershipsBefore = membershipRows();
        flyway().load().migrate();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    @Test
    @DisplayName("AC-57: 既存データは無傷（memberships の既存列も不変）で、再実行しても保留 migration が無い")
    void AC57_既存データ無傷と再実行() throws Exception {
        assertThat(checksums()).isEqualTo(checksumsBefore);
        assertThat(membershipRows()).isEqualTo(membershipsBefore);
        Flyway again = flyway().load();
        assertThat(again.migrate().migrationsExecuted).isZero();
        assertThat(Arrays.stream(again.info().pending())).isEmpty();
    }

    @Test
    @DisplayName("AC-57: memberships の既存行（在籍中・終了済みとも）の effort_share_disposition は NULL")
    void AC57_既存のmembershipsはNULL() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*), SUM(effort_share_disposition IS NULL) FROM memberships")) {
            rs.next();
            assertThat(rs.getLong(1)).as("既存行がある").isGreaterThanOrEqualTo(2);
            assertThat(rs.getLong(2)).isEqualTo(rs.getLong(1));
        }
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'memberships' AND COLUMN_NAME = 'effort_share_disposition'")) {
            assertThat(rs.next()).as("memberships.effort_share_disposition がある").isTrue();
            assertThat(rs.getString(1)).isEqualTo("YES");
            assertThat(rs.getLong(2)).isEqualTo(32);
            assertThat(rs.getString(3)).as("暗黙の既定値を与えない").isNull();
        }
    }

    @Test
    @DisplayName("AC-57: 新2表と文字列列、memberships.effort_share_disposition の照合順序は utf8mb4_0900_ai_ci")
    void AC57_照合順序() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            for (String table : NEW_TABLES) {
                try (ResultSet rs = s.executeQuery("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'")) {
                    assertThat(rs.next()).as(table + " が存在する").isTrue();
                    assertThat(rs.getString(1)).as(table).isEqualTo("utf8mb4_0900_ai_ci");
                }
                try (ResultSet rs = s.executeQuery("SELECT COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLLATION_NAME IS NOT NULL")) {
                    while (rs.next()) {
                        assertThat(rs.getString(2)).as(table + "." + rs.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
                    }
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'memberships' AND COLUMN_NAME = 'effort_share_disposition'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
            }
        }
    }

    @Test
    @DisplayName("AC-57: CHECK 制約が不正な state・版・加入期間 ID・disposition を拒否し、妥当な行は入る")
    void AC57_CHECK拒否() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            List<String> rejected = List.of(
                    share("'BOGUS'", "0", "1", "0"),
                    share("'FAILED'", "0", "1", "0"),
                    share("'PENDING'", "-1", "1", "0"),
                    share("'PENDING'", "0", "0", "0"),
                    share("'PENDING'", "0", "1", "-1"),
                    source(9001, "0", "0", "1"),
                    source(9002, "1", "-1", "1"),
                    source(9003, "1", "0", "0"),
                    "UPDATE memberships SET effort_share_disposition = 'BOGUS' WHERE scope_type = 'TEAM'",
                    "UPDATE memberships SET effort_share_disposition = '' WHERE scope_type = 'TEAM'");
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String sql : rejected) {
                    assertThatThrownBy(() -> s.executeUpdate(sql)).as(sql).isInstanceOf(SQLException.class);
                }
                // 対照: 妥当な行・値は入る（CHECK が全拒否になっていないこと）。
                assertThat(s.executeUpdate(share("'PENDING'", "0", "1", "0"))).isEqualTo(1);
                assertThat(s.executeUpdate(source(9004, "1", "0", "1"))).isEqualTo(1);
                for (String value : List.of("'REVOKED'", "'RETAINED_ACCOUNT_WITHDRAWAL'", "NULL")) {
                    assertThat(s.executeUpdate("UPDATE memberships SET effort_share_disposition = " + value
                            + " WHERE scope_type = 'TEAM' AND left_at IS NOT NULL")).isEqualTo(1);
                }
            } finally {
                s.execute("SET FOREIGN_KEY_CHECKS = 1");
                s.executeUpdate("DELETE FROM effort_team_shares WHERE team_id = 8801");
                s.executeUpdate("DELETE FROM timeline_effort_sources WHERE team_id = 8801");
            }
        }
    }

    @Test
    @DisplayName("AC-57: timeline_effort_sources の FK は timeline_posts の1本だけで実在しない投稿を拒否し、effort_team_shares は FK を持たない")
    void AC57_FKはtimeline_postsだけ() throws Exception {
        Set<String> fks = new HashSet<>();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS "
                    + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND (TABLE_NAME IN ('effort_team_shares','timeline_effort_sources') "
                    + "OR REFERENCED_TABLE_NAME IN ('effort_team_shares','timeline_effort_sources'))")) {
                while (rs.next()) {
                    fks.add(rs.getString(1) + "->" + rs.getString(2));
                }
            }
            assertThat(fks).containsExactly("timeline_effort_sources->timeline_posts");
            assertThatThrownBy(() -> s.executeUpdate(source(9101, "1", "0", "1")))
                    .as("実在しない timeline_posts は FK で拒否").isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("AC-57: チーム側の新2表は組織列を持たず、一意キー・索引は team_id 起点（2026-11-18 裁定）")
    void AC57_チーム側は組織列を持たずteam起点() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME IN ('effort_team_shares','timeline_effort_sources') AND COLUMN_NAME LIKE '%organization%'")) {
                assertThat(rs.next()).as("組織列が無い").isFalse();
            }
            assertThat(indexColumns(s, "effort_team_shares", "uq_effort_team_source")).containsExactly("team_id", "source_record_id");
            assertThat(indexColumns(s, "effort_team_shares", "idx_effort_team_date"))
                    .containsExactly("team_id", "deleted_at", "state", "recorded_on", "created_at", "id");
            assertThat(indexColumns(s, "effort_team_shares", "idx_effort_team_membership"))
                    .containsExactly("team_id", "grant_membership_id", "state");
            assertThat(indexColumns(s, "effort_team_shares", "idx_effort_team_person"))
                    .containsExactly("team_id", "owner_user_id", "deleted_at", "state", "recorded_on");
            assertThat(indexColumns(s, "effort_team_shares", "idx_effort_team_definition"))
                    .containsExactly("team_id", "definition_id", "deleted_at", "state", "recorded_on");
            assertThat(indexColumns(s, "timeline_effort_sources", "idx_timeline_effort_team")).containsExactly("team_id", "is_revoked");
            assertThat(indexColumns(s, "timeline_effort_sources", "uq_timeline_effort_share")).containsExactly("share_uuid");
            assertThat(indexColumns(s, "timeline_effort_sources", "uq_timeline_effort_post")).containsExactly("timeline_post_id");
        }
    }

    @Test
    @DisplayName("AC-57: チーム一覧・加入期間の突合・人/定義の絞り込み・TL 対応表・が設計の索引を使う（EXPLAIN）")
    void AC57_EXPLAINで想定索引() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                // 20 チーム × 30 件の共有と対応表を投入し統計を更新する。
                for (int team = 7001; team <= 7020; team++) {
                    StringBuilder shares = new StringBuilder("INSERT INTO effort_team_shares (id, team_id, owner_user_id, "
                            + "source_record_id, source_version, grant_membership_id, share_version, definition_id, recorded_on, "
                            + "projection_json, projection_hash, state, version, created_at, updated_at) VALUES ");
                    StringBuilder sources = new StringBuilder("INSERT INTO timeline_effort_sources (id, team_id, timeline_post_id, "
                            + "share_uuid, grant_membership_id, share_version, content_revision, is_revoked, created_at, updated_at) VALUES ");
                    for (int i = 0; i < 30; i++) {
                        if (i > 0) {
                            shares.append(',');
                            sources.append(',');
                        }
                        int owner = 100 + i % 10;
                        shares.append("(UNHEX(REPLACE(UUID(),'-','')), ").append(team).append(", ").append(owner)
                                .append(", UNHEX(REPLACE(UUID(),'-','')), 1, ").append(owner * 10 + team % 3)
                                .append(", 1, UNHEX(LPAD(HEX(").append(i % 3).append("), 32, '0')), DATE_SUB('2026-11-01', INTERVAL ")
                                .append(i).append(" DAY), JSON_OBJECT(), REPEAT('a',64), '")
                                .append(i % 5 == 0 ? "REVOKED" : "VISIBLE").append("', 0, NOW(6), NOW(6))");
                        sources.append("(UNHEX(REPLACE(UUID(),'-','')), ").append(team).append(", ").append(team * 100 + i)
                                .append(", UNHEX(LPAD(HEX(").append(team * 100 + i).append("), 32, '0')), 1, 1, 1, ")
                                .append(i % 5 == 0 ? "TRUE" : "FALSE").append(", NOW(6), NOW(6))");
                    }
                    s.executeUpdate(shares.toString());
                    s.executeUpdate(sources.toString());
                }
                s.execute("ANALYZE TABLE effort_team_shares, timeline_effort_sources, memberships");

                assertThat(key(s, "EXPLAIN SELECT id FROM effort_team_shares WHERE team_id = 7005 AND deleted_at IS NULL "
                        + "AND state = 'VISIBLE' ORDER BY recorded_on DESC, created_at DESC, id DESC LIMIT 21"))
                        .isEqualTo("idx_effort_team_date");
                assertThat(key(s, "EXPLAIN SELECT id FROM effort_team_shares WHERE team_id = 7005 AND grant_membership_id = 1002 "
                        + "AND state <> 'REVOKED'")).isEqualTo("idx_effort_team_membership");
                assertThat(key(s, "EXPLAIN SELECT id FROM effort_team_shares WHERE team_id = 7005 AND owner_user_id = 103 "
                        + "AND deleted_at IS NULL AND state = 'VISIBLE' AND recorded_on BETWEEN '2026-10-01' AND '2026-11-01'"))
                        .isEqualTo("idx_effort_team_person");
                assertThat(key(s, "EXPLAIN SELECT id FROM effort_team_shares WHERE team_id = 7005 "
                        + "AND definition_id = UNHEX(LPAD(HEX(1), 32, '0')) AND deleted_at IS NULL AND state = 'VISIBLE' "
                        + "AND recorded_on BETWEEN '2026-10-01' AND '2026-11-01'")).isEqualTo("idx_effort_team_definition");
                assertThat(key(s, "EXPLAIN SELECT id FROM timeline_effort_sources WHERE timeline_post_id = 700503"))
                        .isEqualTo("uq_timeline_effort_post");
                assertThat(key(s, "EXPLAIN SELECT id FROM timeline_effort_sources WHERE share_uuid = UNHEX(LPAD(HEX(700503), 32, '0'))"))
                        .isEqualTo("uq_timeline_effort_share");
            } finally {
                s.executeUpdate("DELETE FROM effort_team_shares WHERE team_id BETWEEN 7001 AND 7020");
                s.executeUpdate("DELETE FROM timeline_effort_sources WHERE team_id BETWEEN 7001 AND 7020");
                s.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────

    private static String share(String state, String sourceVersion, String grantMembershipId, String shareVersion) {
        return "INSERT INTO effort_team_shares (id, team_id, owner_user_id, source_record_id, source_version, grant_membership_id, "
                + "share_version, definition_id, recorded_on, projection_json, projection_hash, state, version, created_at, updated_at) "
                + "VALUES (UNHEX(REPLACE(UUID(),'-','')), 8801, 1, UNHEX(REPLACE(UUID(),'-','')), " + sourceVersion + ", "
                + grantMembershipId + ", " + shareVersion + ", UNHEX(REPLACE(UUID(),'-','')), '2026-11-01', JSON_OBJECT(), "
                + "REPEAT('a',64), " + state + ", 0, NOW(6), NOW(6))";
    }

    private static String source(int postId, String grantMembershipId, String shareVersion, String contentRevision) {
        return "INSERT INTO timeline_effort_sources (id, team_id, timeline_post_id, share_uuid, grant_membership_id, share_version, "
                + "content_revision, is_revoked, created_at, updated_at) VALUES (UNHEX(REPLACE(UUID(),'-','')), 8801, " + postId
                + ", UNHEX(REPLACE(UUID(),'-','')), " + grantMembershipId + ", " + shareVersion + ", " + contentRevision
                + ", FALSE, NOW(6), NOW(6))";
    }

    private static List<String> indexColumns(Statement s, String table, String index) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = '" + table + "' AND INDEX_NAME = '" + index + "' ORDER BY SEQ_IN_INDEX")) {
            while (rs.next()) {
                columns.add(rs.getString(1).toLowerCase());
            }
        }
        return columns;
    }

    private static String key(Statement s, String explain) throws SQLException {
        try (ResultSet rs = s.executeQuery(explain)) {
            assertThat(rs.next()).isTrue();
            return rs.getString("key");
        }
    }

    private List<String> checksums() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("CHECKSUM TABLE users, teams, timeline_posts EXTENDED")) {
            while (rs.next()) {
                result.add(rs.getString(1) + "=" + rs.getString(2));
            }
        }
        return result;
    }

    /** memberships は列追加で CHECKSUM が変わるため、既存列の値を行ごとに比べる（updated_at は CHECK 試験の UPDATE で動きうるので除く）。 */
    private List<String> membershipRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, user_id, scope_type, scope_id, role_kind, joined_at, left_at, leave_reason, "
                     + "invited_by, gdpr_masked_at, created_at FROM memberships ORDER BY id")) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(rs.getString(i)).append('|');
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private MigrationVersion versionBeforeTarget() {
        MigrationInfo[] all = flyway().load().info().all();
        MigrationVersion previous = null;
        for (MigrationInfo info : all) {
            if (info.getScript().endsWith(SCRIPT_SUFFIX)) {
                assertThat(previous).as("PR-3a migration より前の版がある").isNotNull();
                return previous;
            }
            previous = info.getVersion();
        }
        throw new AssertionError("create_effort_team_shares_and_membership_disposition の migration が見つからない");
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
