package com.mannschaft.app.support.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.role.authorization.RolePriority;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回帰: 先に本物の priority の MEMBER(4) がある状態で、誤った priority（ADMIN=100・DEPUTY_ADMIN=80）の行が
 * 残っていても、{@link RoleSeedSupport} が seed と同じ priority へ正し、DEPUTY_ADMIN が MEMBER より
 * 上位（priority が小さい）であることを保証する（CMP-261125-0612）。
 */
@DisplayName("RoleSeedSupport 回帰 IT")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class RoleSeedSupportIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("誤った priority の既存行を seed と同じ値へ正し、MEMBER=4 があっても DEPUTY_ADMIN が上位になる")
    void ensureCanonical_correctsPollutedPriority() {
        polluted("ADMIN", 100);
        polluted("DEPUTY_ADMIN", 80);
        RoleSeedSupport.ensureCanonical(em, "MEMBER");

        RoleSeedSupport.ensureCanonical(em, "ADMIN");
        RoleSeedSupport.ensureCanonical(em, "DEPUTY_ADMIN");

        assertThat(priorityOf("ADMIN")).isEqualTo(RolePriority.priority("ADMIN"));
        assertThat(priorityOf("DEPUTY_ADMIN")).isEqualTo(RolePriority.priority("DEPUTY_ADMIN"));
        assertThat(priorityOf("MEMBER")).isEqualTo(RolePriority.priority("MEMBER"));
        assertThat(priorityOf("DEPUTY_ADMIN")).isLessThan(priorityOf("MEMBER"));
    }

    private void polluted(String name, int priority) {
        em.createNativeQuery("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :name, :priority, 0, NOW(6), NOW(6)) "
                        + "ON DUPLICATE KEY UPDATE priority = VALUES(priority)")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private int priorityOf(String name) {
        return ((Number) em.createNativeQuery("SELECT priority FROM roles WHERE name = :name")
                .setParameter("name", name).getSingleResult()).intValue();
    }
}
