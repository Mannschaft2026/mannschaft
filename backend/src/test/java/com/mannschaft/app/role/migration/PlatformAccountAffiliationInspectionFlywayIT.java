package com.mannschaft.app.role.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役 Z-A PR2 受け入れ条件 P7: 運営アカウント（SYSTEM_ADMIN）のテナント所属が 0 件であることを、
 * 本番と同じ Flyway の全マイグレーション（シード・バックフィル込み）を当てたスキーマで常時固定する。
 *
 * <p>点検 SQL は {@code src/test/resources/platform_account_affiliation/inspection.sql}
 * （設計書 z-a-design.md §2 の点検クエリ＋受諾待ちの招待・申請）を<b>そのまま</b>使う。
 * 殿が本番前に実行する SQL と同一文であることを、本テストが実スキーマで構文・列名ごと保証する。</p>
 *
 * <p>Spring コンテキストは立てない（共有 IT コンテキストを分裂させないため、JDBC と Flyway API だけで行う）。
 * 自己検証として、運営アカウントの所属行を 1 件ずつ植えると点検 SQL が検出することも確かめる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.role.migration.PlatformAccountAffiliationInspectionFlywayIT#isDockerAvailable")
@DisplayName("運営アカウントのテナント所属0件の点検（Flyway 実スキーマ・戦役Z-A PR2 / P7）")
class PlatformAccountAffiliationInspectionFlywayIT {

    private static final String INSPECTION_SQL_RESOURCE = "/platform_account_affiliation/inspection.sql";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_platform_account_affiliation")
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
    void migrate() {
        MYSQL.start();
        MigrateResult result = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();
        assertThat(result.success).as("全マイグレーションの適用が成功すること").isTrue();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    static String inspectionSql() throws IOException {
        try (InputStream in = PlatformAccountAffiliationInspectionFlywayIT.class
                .getResourceAsStream(INSPECTION_SQL_RESOURCE)) {
            assertThat(in).as("点検 SQL が見つからない: " + INSPECTION_SQL_RESOURCE).isNotNull();
            StringBuilder sb = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.strip().startsWith("--")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private List<String> runInspection(Connection c) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(inspectionSql())) {
            while (rs.next()) {
                rows.add(rs.getString("kind") + " user=" + rs.getLong("user_id")
                        + " " + rs.getString("scope_type") + ":" + rs.getString("scope_id")
                        + " " + rs.getString("detail"));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("P7: 全マイグレーション適用後、運営アカウントのテナント所属・所属待ちは 0 件")
    void P7_マイグレーション後の運営アカウント所属は0件() throws Exception {
        try (Connection c = conn()) {
            assertThat(runInspection(c))
                    .as("シード・バックフィルが運営アカウントをテナントへ所属させていないこと")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("P7 自己検証: 運営アカウントの memberships / user_roles のスコープ行を植えると点検 SQL が検出する")
    void P7_点検SQLは植えた所属を検出する() throws Exception {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                long platformUserId = insertUser(c, "z-a-platform@example.com");
                long generalUserId = insertUser(c, "z-a-general@example.com");
                long teamId = insertTeam(c, "z-a-inspection-team");
                insertUserRole(c, platformUserId, "SYSTEM_ADMIN", null);
                // 一般利用者の所属は検出しない（偽陽性の否定）。
                insertMembership(c, generalUserId, teamId);
                assertThat(runInspection(c)).as("一般利用者の所属は点検対象外").isEmpty();

                insertMembership(c, platformUserId, teamId);
                insertUserRole(c, platformUserId, "ADMIN", teamId);

                List<String> rows = runInspection(c);
                assertThat(rows).hasSize(2);
                assertThat(rows).anySatisfy(r -> assertThat(r).startsWith("memberships user=" + platformUserId));
                assertThat(rows).anySatisfy(r -> assertThat(r).startsWith("user_roles user=" + platformUserId));
            } finally {
                c.rollback();
            }
        }
    }

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '点検', '太郎', '点検太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTeam(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at)
                VALUES (?, 'PUBLIC', 1, 0, 0, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "点検チーム");
            ps.setString(2, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertUserRole(Connection c, long userId, String roleName, Long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at)
                SELECT ?, r.id, ?, NULL, NOW(), NOW() FROM roles r WHERE r.name = ?
                """)) {
            ps.setLong(1, userId);
            if (teamId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, teamId);
            }
            ps.setString(3, roleName);
            assertThat(ps.executeUpdate()).as("roles に " + roleName + " がシードされていること").isEqualTo(1);
        }
    }

    private void insertMembership(Connection c, long userId, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at)
                VALUES (?, 'TEAM', ?, 'MEMBER', NOW(), NOW(), NOW())
                """)) {
            ps.setLong(1, userId);
            ps.setLong(2, teamId);
            ps.executeUpdate();
        }
    }
}
