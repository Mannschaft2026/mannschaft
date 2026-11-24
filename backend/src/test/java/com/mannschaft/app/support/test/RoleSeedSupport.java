package com.mannschaft.app.support.test;

import com.mannschaft.app.role.authorization.RolePriority;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * テストが roles 行を用意するときの共通ヘルパ。
 *
 * <p>test プロファイルは ddl-auto=create で Flyway seed（V2.014__seed_roles.sql）が入らず、各 IT が roles を自作する。
 * roles はコミットされるとクラスをまたいで残るため、ある IT が本物と異なる priority（例: ADMIN=100）で先に
 * 作ると、同じ JVM の後続 IT の実効ロール解決（priority 最小のロールを採る）が崩れ、順序依存で落ちる。
 * ここでは seed と同じ priority（{@link RolePriority}）で投入し、既存行の誤った priority も
 * {@code ON DUPLICATE KEY UPDATE} で正す。</p>
 */
public final class RoleSeedSupport {

    /** seed に無いロール名のときの既定 priority（最弱側。{@link RolePriority} の不明値は使わない）。 */
    public static final int UNKNOWN_ROLE_PRIORITY = 99;

    private static final String UPSERT_SQL =
            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, NOW(6), NOW(6)) "
                    + "ON DUPLICATE KEY UPDATE priority = VALUES(priority)";

    private RoleSeedSupport() {
        throw new AssertionError("utility class");
    }

    /** seed 表にあるロール名は seed の priority、無ければ fallback を返す。 */
    public static int priorityOf(String name, int fallback) {
        int p = RolePriority.priority(name);
        return p == Integer.MAX_VALUE ? fallback : p;
    }

    /** roles に name の行を seed と同じ priority で用意する（既存行の priority も正す）。 */
    public static void ensureCanonical(EntityManager em, String name) {
        em.createNativeQuery("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :name, :priority, :sys, NOW(6), NOW(6)) "
                        + "ON DUPLICATE KEY UPDATE priority = VALUES(priority)")
                .setParameter("name", name)
                .setParameter("priority", priorityOf(name, UNKNOWN_ROLE_PRIORITY))
                .setParameter("sys", "SYSTEM_ADMIN".equals(name) ? 1 : 0)
                .executeUpdate();
    }

    /** {@link #ensureCanonical(EntityManager, String)} の JdbcTemplate 版。 */
    public static void ensureCanonical(JdbcTemplate jdbc, String name) {
        jdbc.update(UPSERT_SQL, name, name, priorityOf(name, UNKNOWN_ROLE_PRIORITY),
                "SYSTEM_ADMIN".equals(name) ? 1 : 0);
    }
}
