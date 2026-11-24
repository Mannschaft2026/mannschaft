package com.mannschaft.app.cms.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261123-2341: ブログ記事 slug の重複是正と、生存行の一意制約（V267）を実 MySQL/Flyway で固定する。
 * 歴史スキーマ（V266 時点）へ SQL で重複行を投入してから最新へ移行する。
 *
 * <p>是正規則: 生存行の同一スコープ・同一 slug のうち PUBLISHED 優先・同順位なら id 小を残し、残りへ連番を付ける。</p>
 */
@EnabledIf("com.mannschaft.app.shift.migration.FlywayExistingDataShiftScheduleSetNullFkMigrationTest#isDockerAvailable")
class FlywayExistingDataBlogPostsSlugActiveUniqueMigrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("blog_slug_unique").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    @BeforeAll
    static void start() {
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void 重複を是正して生存行の一意制約を張る() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("266.20261122100130")).load().migrate();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            // チーム1: 検証環境の実データ（114 = DRAFT(14)・PUBLISHED(15)、e2en9 = DRAFT(2)・PUBLISHED(3)）
            insert(st, 14, "team_id", 1, "114", "DRAFT", false);
            insert(st, 15, "team_id", 1, "114", "PUBLISHED", false);
            insert(st, 2, "team_id", 1, "e2en9", "DRAFT", false);
            insert(st, 3, "team_id", 1, "e2en9", "PUBLISHED", false);
            // 同順位（どちらも DRAFT）は id が小さい方を残す。連番候補 "same-2" が既に別記事に使われている。
            insert(st, 20, "team_id", 1, "same", "DRAFT", false);
            insert(st, 21, "team_id", 1, "same", "DRAFT", false);
            insert(st, 22, "team_id", 1, "same-2", "DRAFT", false);
            // 別チームの同 slug・論理削除済みの同 slug は是正対象外
            insert(st, 30, "team_id", 2, "114", "PUBLISHED", false);
            insert(st, 31, "team_id", 1, "114", "PUBLISHED", true);
            // 組織・個人スコープ
            insert(st, 40, "organization_id", 5, "org-dup", "PUBLISHED", false);
            insert(st, 41, "organization_id", 5, "org-dup", "PUBLISHED", false);
            insert(st, 50, "user_id", 9, "me-dup", "DRAFT", false);
            insert(st, 51, "user_id", 9, "me-dup", "DRAFT", false);
        }

        Flyway latest = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(latest.migrate().success).isTrue();

        try (Connection c = connection(); Statement st = c.createStatement()) {
            Map<Long, String> slugs = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT id, slug FROM blog_posts ORDER BY id")) {
                while (rs.next()) {
                    slugs.put(rs.getLong(1), rs.getString(2));
                }
            }
            // PUBLISHED が残り、DRAFT 側に連番
            assertThat(slugs.get(15L)).isEqualTo("114");
            assertThat(slugs.get(14L)).isEqualTo("114-2");
            assertThat(slugs.get(3L)).isEqualTo("e2en9");
            assertThat(slugs.get(2L)).isEqualTo("e2en9-2");
            // 同順位は id 小が残る。連番が既存 slug と衝突する側は id 付きで一意化
            assertThat(slugs.get(20L)).isEqualTo("same");
            assertThat(slugs.get(21L)).isEqualTo("same-dup-21");
            assertThat(slugs.get(22L)).isEqualTo("same-2");  // 既存の別 slug は動かさない
            // 対象外
            assertThat(slugs.get(30L)).isEqualTo("114");
            assertThat(slugs.get(31L)).isEqualTo("114");
            // 組織・個人
            assertThat(slugs.get(40L)).isEqualTo("org-dup");
            assertThat(slugs.get(41L)).isEqualTo("org-dup-2");
            assertThat(slugs.get(50L)).isEqualTo("me-dup");
            assertThat(slugs.get(51L)).isEqualTo("me-dup-2");

            // 制約: 生存行の重複は deleted_at NULL でも拒否、別スコープ・削除済みは許可
            assertThatThrownBy(() -> insert(st, 60, "team_id", 1, "114", "DRAFT", false))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(st, 61, "organization_id", 5, "org-dup", "DRAFT", false))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insert(st, 62, "user_id", 9, "me-dup", "DRAFT", false))
                    .isInstanceOf(SQLException.class);
            insert(st, 63, "team_id", 3, "114", "DRAFT", false);
            insert(st, 64, "team_id", 1, "114", "DRAFT", true);
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** scopeColumn は本クラスの固定値のみ。 */
    private static void insert(Statement st, long id, String scopeColumn, long scopeId, String slug,
                               String status, boolean deleted) throws SQLException {
        st.executeUpdate("INSERT INTO blog_posts (id, " + scopeColumn + ", title, slug, body, status, "
                + "allow_comments, pinned, cross_post_to_timeline, created_at, updated_at, deleted_at) VALUES ("
                + id + ", " + scopeId + ", 't', '" + slug + "', 'b', '" + status + "', 0, 0, 0, NOW(), NOW(), "
                + (deleted ? "NOW()" : "NULL") + ")");
    }
}
