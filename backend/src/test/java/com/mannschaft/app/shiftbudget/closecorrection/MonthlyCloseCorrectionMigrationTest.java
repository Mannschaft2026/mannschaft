package com.mannschaft.app.shiftbudget.closecorrection;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260909-2152 試練14（Flyway 実DB metadata）・試練17（budget_transactions 保護トリガー）・
 * 試練08 の DB 部分（訂正表から仕訳への FK RESTRICT）。
 *
 * <p>統合テストのスキーマは Hibernate 生成（Flyway 無効）であり、トリガー・FK・照合順序は存在しない。
 * よって本試験は Spring を起動せず、専用の Testcontainers MySQL 8.0 に全 migration を Flyway で適用し、
 * 実 DB の information_schema と実 SQL の挙動で検査する（金型: FlywayExistingDataBudgetMiscSetNullFkMigrationTest）。</p>
 *
 * <p>試練段階では新表・トリガーの migration を置かないため、metadata の存在検査と
 * 「保護対象の DELETE / UPDATE が SIGNAL で失敗する」検査がアサーション失敗で red になる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.shiftbudget.closecorrection.MonthlyCloseCorrectionMigrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 締め後訂正の Flyway 実DB（表・制約・照合順序・保護トリガー）")
class MonthlyCloseCorrectionMigrationTest {

    private static final String RECORDS = "budget_monthly_close_records";
    private static final String CORRECTIONS = "budget_monthly_close_corrections";
    private static final String EVENTS = "shift_budget_monthly_close_events";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_close_correction")
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
                .load()
                .migrate();
        assertThat(result.success).as("全 migration の適用").isTrue();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    // ───────────── 試練14 表・列・制約・照合順序 ─────────────

    @Test
    @DisplayName("試練14 新表3つが utf8mb4_0900_ai_ci で作られ、金額精度・NOT NULL・NULL可が設計どおり")
    void 試練14_新表と列定義() throws Exception {
        try (Connection c = conn()) {
            for (String table : List.of(RECORDS, CORRECTIONS, EVENTS)) {
                assertThat(tableCollation(c, table)).as("%s の照合順序", table).isEqualTo("utf8mb4_0900_ai_ci");
            }
            assertThat(columnType(c, RECORDS, "original_amount")).isEqualTo("decimal(12,0)");
            assertThat(columnType(c, RECORDS, "original_exact_total")).isEqualTo("decimal(14,2)");
            assertThat(columnType(c, RECORDS, "exact_total")).isEqualTo("decimal(14,2)");
            assertThat(columnType(c, RECORDS, "posted_total")).isEqualTo("decimal(12,0)");
            assertThat(nullable(c, RECORDS, "close_actor_kind")).isEqualTo("NO");
            assertThat(nullable(c, RECORDS, "close_actor_user_id")).isEqualTo("YES");
            assertThat(columnType(c, CORRECTIONS, "exact_delta")).isEqualTo("decimal(12,2)");
            assertThat(columnType(c, CORRECTIONS, "posted_delta")).isEqualTo("decimal(12,0)");
            assertThat(columnType(c, CORRECTIONS, "exact_month_total_after")).isEqualTo("decimal(14,2)");
            assertThat(columnType(c, CORRECTIONS, "reason")).isEqualTo("varchar(500)");
            assertThat(nullable(c, CORRECTIONS, "reason")).isEqualTo("NO");
            assertThat(columnType(c, CORRECTIONS, "content")).isEqualTo("varchar(500)");
            assertThat(columnType(c, CORRECTIONS, "command_id")).isEqualTo("binary(16)");
            assertThat(columnType(c, CORRECTIONS, "request_hash")).isEqualTo("char(64)");
            assertThat(nullable(c, CORRECTIONS, "line_kind")).isEqualTo("NO");
            assertThat(nullable(c, CORRECTIONS, "consumption_id")).isEqualTo("YES");
            assertThat(nullable(c, CORRECTIONS, "added_line_id")).isEqualTo("YES");
            assertThat(nullable(c, CORRECTIONS, "slot_id")).isEqualTo("YES");
            assertThat(nullable(c, EVENTS, "actor_user_id")).isEqualTo("YES");
            assertThat(nullable(c, EVENTS, "actor_kind")).isEqualTo("NO");
        }
    }

    @Test
    @DisplayName("試練14 UNIQUE（原仕訳・command・取消元・差額仕訳・締め記録×revision）と索引（明細×revision）")
    void 試練14_一意制約と索引() throws Exception {
        try (Connection c = conn()) {
            assertThat(uniqueIndexColumns(c, RECORDS)).contains(List.of("original_transaction_id"));
            List<List<String>> correctionUniques = uniqueIndexColumns(c, CORRECTIONS);
            assertThat(correctionUniques).contains(List.of("command_id"), List.of("reverses_correction_id"),
                    List.of("budget_transaction_id"), List.of("close_record_id", "revision"));
            assertThat(indexLeadingColumns(c, CORRECTIONS)).contains(List.of("consumption_id", "revision"));
        }
    }

    @Test
    @DisplayName("試練14/試練08 訂正表から仕訳・締め記録への FK は削除 RESTRICT（物理削除を DB で止める）")
    void 試練14_FK_RESTRICT() throws Exception {
        try (Connection c = conn()) {
            assertThat(foreignKey(c, RECORDS, "original_transaction_id")).isEqualTo("budget_transactions:RESTRICT");
            assertThat(foreignKey(c, CORRECTIONS, "close_record_id")).isEqualTo(RECORDS + ":RESTRICT");
            assertThat(foreignKey(c, CORRECTIONS, "budget_transaction_id")).isEqualTo("budget_transactions:RESTRICT");
        }
    }

    @Test
    @DisplayName("試練14 line_kind は ORIGINAL なら consumption_id、ADDED なら added_line_id の一方のみ（CHECK）")
    void 試練14_line_kindのCHECK() throws Exception {
        try (Connection c = conn()) {
            List<String> checks = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc "
                    + "JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA "
                    + "AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA = DATABASE() "
                    + "AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'")) {
                ps.setString(1, CORRECTIONS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        checks.add(rs.getString(1));
                    }
                }
            }
            assertThat(checks).anySatisfy(clause -> assertThat(clause)
                    .contains("line_kind").contains("consumption_id").contains("added_line_id"));
        }
    }

    // ───────────── 試練17 保護トリガー（AC5改） ─────────────

    @Test
    @DisplayName("試練17 budget_transactions に BEFORE DELETE / BEFORE UPDATE トリガーがある")
    void 試練17_トリガーが存在する() throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("SELECT ACTION_TIMING, EVENT_MANIPULATION FROM "
                     + "information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA = DATABASE() "
                     + "AND EVENT_OBJECT_TABLE = 'budget_transactions'")) {
            List<String> triggers = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    triggers.add(rs.getString(1) + " " + rs.getString(2));
                }
            }
            assertThat(triggers).contains("BEFORE DELETE", "BEFORE UPDATE");
        }
    }

    @Test
    @DisplayName("試練17 月次仕訳・訂正仕訳は SQL の DELETE と保護列 UPDATE が SIGNAL で失敗し、保護外の列と他 source 行は従来どおり")
    void 試練17_保護トリガーの挙動() throws Exception {
        try (Connection c = conn()) {
            for (String source : List.of("SHIFT_BUDGET_MONTHLY", "SHIFT_BUDGET_CORRECTION")) {
                long id = insertTransaction(c, source);
                for (String update : List.of(
                        "UPDATE budget_transactions SET amount = amount + 1 WHERE id = ?",
                        "UPDATE budget_transactions SET transaction_type = 'INCOME' WHERE id = ?",
                        "UPDATE budget_transactions SET transaction_date = '2026-07-01' WHERE id = ?",
                        "UPDATE budget_transactions SET fiscal_year_id = fiscal_year_id + 1 WHERE id = ?",
                        "UPDATE budget_transactions SET category_id = category_id + 1 WHERE id = ?",
                        "UPDATE budget_transactions SET scope_id = scope_id + 1 WHERE id = ?",
                        "UPDATE budget_transactions SET source_type = 'MANUAL' WHERE id = ?",
                        "UPDATE budget_transactions SET source_id = source_id + 1 WHERE id = ?",
                        "UPDATE budget_transactions SET deleted_at = NOW() WHERE id = ?")) {
                    assertThatThrownBy(() -> execute(c, update, id))
                            .as("%s: %s は保護トリガーで拒否されること", source, update)
                            .isInstanceOf(SQLException.class);
                }
                assertThatThrownBy(() -> execute(c, "DELETE FROM budget_transactions WHERE id = ?", id))
                        .as("%s の物理削除はトリガーで拒否されること", source)
                        .isInstanceOf(SQLException.class);
                assertThatCode(() -> execute(c, "UPDATE budget_transactions SET description = '注記' WHERE id = ?", id))
                        .as("保護外の列は更新できる")
                        .doesNotThrowAnyException();
                assertThat(count(c, id)).isEqualTo(1);
            }
            long manual = insertTransaction(c, null);
            assertThatCode(() -> execute(c, "UPDATE budget_transactions SET amount = 1 WHERE id = ?", manual))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(c, "DELETE FROM budget_transactions WHERE id = ?", manual))
                    .as("シフト予算以外の取引は従来どおり削除できる")
                    .doesNotThrowAnyException();
        }
    }

    // ───────────── ヘルパー ─────────────

    private long insertTransaction(Connection c, String sourceType) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO budget_transactions (fiscal_year_id, category_id, "
                + "scope_type, scope_id, transaction_type, amount, transaction_date, title, is_auto_recorded, source_type, "
                + "source_id, approval_status, recorded_by) VALUES (900001, 900002, 'TEAM', 900003, 'EXPENSE', 10000, "
                + "'2026-06-30', '2152 保護対象', 1, ?, 900004, 'APPROVED', 900005)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sourceType);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } finally {
            try (Statement s = c.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private static void execute(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static int count(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM budget_transactions WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String tableCollation(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "（表が無い）";
            }
        }
    }

    private static String columnType(Connection c, String table, String column) throws SQLException {
        return columnAttr(c, table, column, "COLUMN_TYPE");
    }

    private static String nullable(Connection c, String table, String column) throws SQLException {
        return columnAttr(c, table, column, "IS_NULLABLE");
    }

    private static String columnAttr(Connection c, String table, String column, String attr) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + attr + " FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "（列が無い: " + table + "." + column + "）";
            }
        }
    }

    private static List<List<String>> uniqueIndexColumns(Connection c, String table) throws SQLException {
        return indexColumns(c, table, true);
    }

    private static List<List<String>> indexLeadingColumns(Connection c, String table) throws SQLException {
        return indexColumns(c, table, false);
    }

    private static List<List<String>> indexColumns(Connection c, String table, boolean uniqueOnly) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                + (uniqueOnly ? "AND NON_UNIQUE = 0 " : "") + "GROUP BY INDEX_NAME")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(List.of(rs.getString(2).split(",")));
                }
            }
        }
        return result;
    }

    private static String foreignKey(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT k.REFERENCED_TABLE_NAME, r.DELETE_RULE FROM "
                + "information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                + "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                + "WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = ? AND k.COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "（FK が無い: " + table + "." + column + "）";
                }
                String rule = rs.getString(2);
                // MySQL は RESTRICT と NO ACTION を同義に扱う（InnoDB は即時検査）
                return rs.getString(1) + ":" + ("NO ACTION".equals(rule) ? "RESTRICT" : rule);
            }
        }
    }
}
