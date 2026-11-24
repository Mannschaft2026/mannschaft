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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>action_memo_daily_posts 作成 migration（V267）の既存データ backfill の番人テスト（CMP-261123-1201）。</b>
 *
 * <p>backfill は「投稿が生存・本人の PERSONAL 投稿・本文の日付見出しがメモの memo_date と一致」を満たす
 * (user_id, memo_date) だけを入れる。別の日へ移されたメモ（旧実装は日付変更後も投稿 ID を保持していた）の
 * 現在の memo_date を公開日とみなさないこと、削除済み投稿・他人／TEAM 投稿・投稿 ID なしは入れないこと、
 * 複数候補は最大の投稿 ID を採ることを検証する。</p>
 *
 * <p>方針は既存の {@code FlywayExistingData*MigrationTest} に倣い、Spring を起動せず Testcontainers の実 MySQL 8.0 に
 * Flyway を直接実行する。V267 直前（{@value #PRE_TARGET}）まで適用 → 既存行をシード → 残りを適用して検証する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.actionmemo.migration.FlywayExistingDataActionMemoDailyPostsMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ action_memo_daily_posts backfill（V267）番人テスト")
class FlywayExistingDataActionMemoDailyPostsMigrationTest {

    /** V267 の直前バージョン（origin/main 全体最大）。 */
    private static final String PRE_TARGET = "266.20261122100130";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_action_memo_daily_posts")
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
    @DisplayName("条件を満たす (user, date) だけが backfill され、別日へ移されたメモ・削除済み・他人／TEAM 投稿の ID は入らない")
    void 条件を満たす行だけがbackfillされる() throws Exception {
        MigrateResult pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_TARGET))
                .load()
                .migrate();
        assertThat(pre.success).as("V267 直前までの適用が成功すること").isTrue();

        final long userId;
        final long otherUserId;
        final long goodPost;
        final long movedMemoPost;
        final long deletedPost;
        final long teamPost;
        final long othersPost;
        final long multiLow;
        final long multiHigh;
        final long deletedMemoOnlyPost;
        try (Connection c = conn()) {
            assertThat(tableExists(c, "action_memo_daily_posts")).as("V267 直前には表は無い").isFalse();

            userId = insertUser(c, "daily-post-user@example.com");
            otherUserId = insertUser(c, "daily-post-other@example.com");
            long teamId = insertTeam(c);

            // 1) 条件をすべて満たす: 2026-03-10 の見出し・メモも 03-10
            goodPost = insertPost(c, "PERSONAL", userId, userId, heading("2026-03-10"), false);
            insertMemo(c, userId, "2026-03-10", goodPost, false);

            // 2) 別日へ移されたメモ: 投稿の見出しは 03-11 だがメモの現在の memo_date は 03-12（旧実装は ID を保持した）
            movedMemoPost = insertPost(c, "PERSONAL", userId, userId, heading("2026-03-11"), false);
            insertMemo(c, userId, "2026-03-12", movedMemoPost, false);

            // 3) 投稿が削除済み
            deletedPost = insertPost(c, "PERSONAL", userId, userId, heading("2026-03-13"), true);
            insertMemo(c, userId, "2026-03-13", deletedPost, false);

            // 4) TEAM 投稿を指している（見出しは一致していても入れない）
            teamPost = insertPost(c, "TEAM", teamId, userId, heading("2026-03-14"), false);
            insertMemo(c, userId, "2026-03-14", teamPost, false);

            // 5) 他人の PERSONAL 投稿を指している
            othersPost = insertPost(c, "PERSONAL", otherUserId, otherUserId, heading("2026-03-15"), false);
            insertMemo(c, userId, "2026-03-15", othersPost, false);

            // 6) 投稿 ID なし
            insertMemo(c, userId, "2026-03-16", null, false);

            // 7) 複数候補は最大 ID
            multiLow = insertPost(c, "PERSONAL", userId, userId, heading("2026-03-17"), false);
            multiHigh = insertPost(c, "PERSONAL", userId, userId, heading("2026-03-17"), false);
            insertMemo(c, userId, "2026-03-17", multiLow, false);
            insertMemo(c, userId, "2026-03-17", multiHigh, false);

            // 8) 参照が論理削除済みメモにしか残っていなくても、投稿が生存・見出し一致なら入る
            deletedMemoOnlyPost = insertPost(c, "PERSONAL", userId, userId, heading("2026-03-18"), false);
            insertMemo(c, userId, "2026-03-18", deletedMemoOnlyPost, true);
        }

        MigrateResult rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
        assertThat(rest.success).as("V267 を含む残りの migration が成功すること").isTrue();

        try (Connection c = conn()) {
            Map<String, Long> rows = dailyPosts(c, userId);
            assertThat(rows.get("2026-03-10")).as("条件を満たす行は入る").isEqualTo(goodPost);
            assertThat(rows).as("別日へ移されたメモの現在日付（03-12）には入らない").doesNotContainKey("2026-03-12");
            assertThat(rows).as("投稿の見出し日（03-11）はメモが無いので入らない").doesNotContainKey("2026-03-11");
            assertThat(rows).as("削除済み投稿は入らない").doesNotContainKey("2026-03-13");
            assertThat(rows).as("TEAM 投稿は入らない").doesNotContainKey("2026-03-14");
            assertThat(rows).as("他人の投稿は入らない").doesNotContainKey("2026-03-15");
            assertThat(rows).as("投稿 ID なしは入らない").doesNotContainKey("2026-03-16");
            assertThat(rows.get("2026-03-17")).as("複数候補は最大の投稿 ID").isEqualTo(multiHigh).isGreaterThan(multiLow);
            assertThat(rows.get("2026-03-18")).as("論理削除済みメモ経由でも生存・見出し一致なら入る").isEqualTo(deletedMemoOnlyPost);
            assertThat(rows).hasSize(3);
            assertThat(movedMemoPost).isNotIn(rows.values());
            assertThat(dailyPosts(c, otherUserId)).as("他人の投稿を指すメモは、他人の行も作らない").isEmpty();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private static String heading(String date) {
        return "## " + date + " の行動ログ\n\n- 09:00 メモ\n";
    }

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
                VALUES ('日次記録チーム', 'PUBLIC', 'am-daily-posts-migration', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private long insertPost(Connection c, String scopeType, long scopeId, long userId, String content, boolean deleted)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timeline_posts (scope_type, scope_id, user_id, content, deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, userId);
            ps.setString(4, content);
            ps.setObject(5, deleted ? java.time.LocalDateTime.of(2026, 3, 20, 0, 0) : null);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private long insertMemo(Connection c, long userId, String memoDate, Long postId, boolean deleted)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO action_memos
                    (user_id, memo_date, content, category, timeline_post_id, deleted_at, created_at, updated_at)
                VALUES (?, ?, '行動ログ', 'WORK', ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, memoDate);
            ps.setObject(3, postId);
            ps.setObject(4, deleted ? java.time.LocalDateTime.of(2026, 3, 21, 0, 0) : null);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private static Map<String, Long> dailyPosts(Connection c, long userId) throws SQLException {
        Map<String, Long> m = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DATE_FORMAT(memo_date, '%Y-%m-%d'), timeline_post_id FROM action_memo_daily_posts WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return m;
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
