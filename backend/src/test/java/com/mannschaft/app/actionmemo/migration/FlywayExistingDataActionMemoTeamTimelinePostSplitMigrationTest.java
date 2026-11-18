package com.mannschaft.app.actionmemo.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>AC-12: action_memos のチーム投稿ID分離 migration（V263）の既存データ移行の番人テスト。</b>
 *
 * <p>V263 は {@code action_memos.team_timeline_post_id} を追加し、{@code timeline_post_id} が
 * {@code timeline_posts.scope_type='TEAM'} を指していた既存行を新列へ移して {@code timeline_post_id} を NULL にする。
 * PERSONAL を指す行・NULL の行は不変であること。</p>
 *
 * <p>方針: 既存の {@code FlywayExistingData*MigrationTest} に倣い、Spring を起動せず Testcontainers の
 * 実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。V263 直前（origin/main 全体の最大
 * {@value #PRE_V263_TARGET}）まで適用 → 既存行をシード → 残りを適用して検証する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.actionmemo.migration.FlywayExistingDataActionMemoTeamTimelinePostSplitMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ action_memos チーム投稿ID分離（V263）番人テスト")
class FlywayExistingDataActionMemoTeamTimelinePostSplitMigrationTest {

    /** V263 の直前バージョン（origin/main 全体最大）。 */
    private static final String PRE_V263_TARGET = "262.20261118004157";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_action_memos_team_post_split")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
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

    @Test
    @DisplayName("AC-12 TEAM 投稿を指す既存行は team_timeline_post_id へ移り timeline_post_id は NULL、PERSONAL・NULL の行は不変")
    void 既存データのチーム投稿IDが新列へ移される() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V263_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V263 直前までの適用が成功すること").isTrue();

        final long teamPostId;
        final long personalPostId;
        final long teamMemoId;
        final long personalMemoId;
        final long unpostedMemoId;
        try (Connection c = conn()) {
            assertThat(columnExists(c, "action_memos", "team_timeline_post_id"))
                    .as("V263 直前には team_timeline_post_id 列はまだ無い").isFalse();

            long userId = insertUser(c, "am-split-migration@example.com");
            long teamId = insertTeam(c);
            teamPostId = insertTimelinePost(c, "TEAM", teamId, userId);
            personalPostId = insertTimelinePost(c, "PERSONAL", userId, userId);
            teamMemoId = insertActionMemo(c, userId, teamPostId, teamId);
            personalMemoId = insertActionMemo(c, userId, personalPostId, null);
            unpostedMemoId = insertActionMemo(c, userId, null, null);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V263 を含む残りの migration が成功すること").isTrue();

        try (Connection c = conn()) {
            assertThat(columnExists(c, "action_memos", "team_timeline_post_id"))
                    .as("V263 で team_timeline_post_id 列が追加される").isTrue();

            assertThat(longColumn(c, teamMemoId, "team_timeline_post_id"))
                    .as("TEAM 投稿を指していた行は新列へ移る").isEqualTo(teamPostId);
            assertThat(longColumn(c, teamMemoId, "timeline_post_id"))
                    .as("TEAM 投稿を指していた行の timeline_post_id は NULL").isNull();

            assertThat(longColumn(c, personalMemoId, "timeline_post_id"))
                    .as("PERSONAL 投稿を指す行は不変").isEqualTo(personalPostId);
            assertThat(longColumn(c, personalMemoId, "team_timeline_post_id")).isNull();

            assertThat(longColumn(c, unpostedMemoId, "timeline_post_id")).isNull();
            assertThat(longColumn(c, unpostedMemoId, "team_timeline_post_id")).isNull();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, 'メモ', '太郎', 'メモ太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private long insertTeam(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams (name, visibility, slug, created_at, updated_at)
                VALUES ('列分割チーム', 'PUBLIC', 'am-split-migration', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private long insertTimelinePost(Connection c, String scopeType, long scopeId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timeline_posts (scope_type, scope_id, user_id, content, created_at, updated_at)
                VALUES (?, ?, ?, '行動メモ投稿', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, userId);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private long insertActionMemo(Connection c, long userId, Long timelinePostId, Long postedTeamId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO action_memos
                    (user_id, memo_date, content, category, timeline_post_id, posted_team_id, created_at, updated_at)
                VALUES (?, CURDATE(), '今日の行動ログ', 'WORK', ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setObject(2, timelinePostId);
            ps.setObject(3, postedTeamId);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Long longColumn(Connection c, long memoId, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM action_memos WHERE id = ?")) {
            ps.setLong(1, memoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
