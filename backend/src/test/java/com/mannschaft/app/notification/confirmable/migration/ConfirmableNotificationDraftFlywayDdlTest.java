package com.mannschaft.app.notification.confirmable.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261126-1558 試練 AC6: {@code confirmable_notification_drafts} の migration を実 MySQL（Testcontainers）に
 * fresh から全適用し、列定義・索引・JSON の往復を検証する。モックなし・実 Flyway。
 *
 * <p>通常の IT は ddl-auto=create で Flyway を走らせないため、本クラスが DDL の担保になる
 * （金型: {@code OrgTeamGroupsFlywayDdlTest}）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("com.mannschaft.app.notification.confirmable.migration.ConfirmableNotificationDraftFlywayDdlTest#isDockerAvailable")
@DisplayName("CMP-261126-1558 AC6: 確認通知の下書き Flyway DDL")
class ConfirmableNotificationDraftFlywayDdlTest {

    private static final String TABLE = "confirmable_notification_drafts";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_cn_drafts_ddl")
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

    @Test
    @Order(1)
    @DisplayName("AC6: fresh から全 migration が成功し、confirmable_notification_drafts が作られる")
    void migrationApplies() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        assertThat(tableExists()).as("confirmable_notification_drafts が存在する").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("AC6: 列の型・NULL可否・既定値が設計どおり")
    void columnsMatchDesign() throws Exception {
        Map<String, Map<String, String>> columns = columns();
        assertThat(columns).as("列一覧").containsOnlyKeys(
                "id", "scope_type", "scope_id", "user_id", "title", "body", "priority", "deadline_at",
                "audience_mode", "selected_targets", "selected_group_id", "selected_template_id",
                "version", "created_at", "updated_at");

        assertThat(columns.get("id").get("COLUMN_TYPE")).isEqualTo("binary(16)");
        assertThat(columns.get("scope_type").get("COLUMN_TYPE")).isEqualTo("enum('TEAM','ORGANIZATION')");
        assertThat(columns.get("scope_id").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get("user_id").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get("title").get("COLUMN_TYPE")).isEqualTo("varchar(200)");
        assertThat(columns.get("title").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(columns.get("title").get("COLUMN_DEFAULT")).as("title DEFAULT ''").isEqualTo("");
        assertThat(columns.get("body").get("DATA_TYPE")).isEqualTo("text");
        assertThat(columns.get("priority").get("COLUMN_TYPE")).isEqualTo("enum('NORMAL','HIGH','URGENT')");
        assertThat(columns.get("priority").get("COLUMN_DEFAULT")).isEqualTo("NORMAL");
        assertThat(columns.get("deadline_at").get("COLUMN_TYPE")).isEqualTo("datetime(6)");
        assertThat(columns.get("audience_mode").get("COLUMN_TYPE")).isEqualTo("enum('DEFAULT','TARGETS','GROUP')");
        assertThat(columns.get("selected_targets").get("DATA_TYPE")).isEqualTo("json");
        assertThat(columns.get("selected_group_id").get("COLUMN_TYPE")).isEqualTo("binary(16)");
        assertThat(columns.get("selected_template_id").get("COLUMN_TYPE")).isEqualTo("bigint unsigned");
        assertThat(columns.get("version").get("DATA_TYPE")).isEqualTo("bigint");
        assertThat(columns.get("version").get("IS_NULLABLE")).isEqualTo("NO");
    }

    @Test
    @Order(3)
    @DisplayName("AC6: 索引は (scope_type, scope_id, user_id, updated_at)・(user_id)・(updated_at)。一意制約は無く FK も張らない")
    void indexesMatchDesign() throws Exception {
        Map<String, List<String>> nonUnique = new HashMap<>();
        List<String> uniqueNonPrimary = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
            ps.setString(1, MYSQL.getDatabaseName());
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if ("PRIMARY".equals(name)) {
                        continue;
                    }
                    if (rs.getInt("NON_UNIQUE") == 0) {
                        uniqueNonPrimary.add(name);
                    }
                    nonUnique.computeIfAbsent(name, k -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        assertThat(nonUnique.values()).as("索引の列構成").containsExactlyInAnyOrder(
                List.of("scope_type", "scope_id", "user_id", "updated_at"),
                List.of("user_id"),
                List.of("updated_at"));
        assertThat(uniqueNonPrimary).as("一意制約は付けない").isEmpty();

        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, MYSQL.getDatabaseName());
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("FK は張らない（user_id はクロスドメイン）").isZero();
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("AC6: 最小の INSERT で既定値が入り、selected_targets の JSON が往復する")
    void jsonRoundTripAndDefaults() throws Exception {
        String json = "[{\"type\":\"TEAM\",\"id\":12},{\"type\":\"ORGANIZATION\",\"id\":34}]";
        try (Connection c = conn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + TABLE + " (id, scope_type, scope_id, user_id, audience_mode, selected_targets, "
                            + "version, created_at, updated_at) "
                            + "VALUES (UNHEX(REPLACE(UUID(),'-','')), 'TEAM', 1, 2, 'TARGETS', ?, 0, NOW(6), NOW(6))")) {
                ps.setString(1, json);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT title, priority, selected_targets FROM " + TABLE + " WHERE scope_id = 1 AND user_id = 2");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("title")).isEqualTo("");
                assertThat(rs.getString("priority")).isEqualTo("NORMAL");
                ObjectMapper mapper = new ObjectMapper();
                JsonNode stored = mapper.readTree(rs.getString("selected_targets"));
                assertThat(stored).isEqualTo(mapper.readTree(json));
            }
        }
    }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static boolean tableExists() throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, MYSQL.getDatabaseName());
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static Map<String, Map<String, String>> columns() throws SQLException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                        + "ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, MYSQL.getDatabaseName());
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> attrs = new HashMap<>();
                    attrs.put("COLUMN_TYPE", rs.getString("COLUMN_TYPE"));
                    attrs.put("DATA_TYPE", rs.getString("DATA_TYPE"));
                    attrs.put("IS_NULLABLE", rs.getString("IS_NULLABLE"));
                    attrs.put("COLUMN_DEFAULT", rs.getString("COLUMN_DEFAULT"));
                    result.put(rs.getString("COLUMN_NAME"), attrs);
                }
            }
        }
        return result;
    }
}
