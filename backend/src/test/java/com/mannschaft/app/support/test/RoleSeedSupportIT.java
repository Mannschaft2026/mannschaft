package com.mannschaft.app.support.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.authorization.RolePriority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 回帰: 誤った priority の roles 行が <b>commit されて残っていても</b>、{@link RoleSeedSupport} が seed と同じ
 * priority へ正し、{@code AccessControlService.hasRoleOrAbove} の判定が本番と同じになることを保証する
 * （CMP-261125-0612 / CMP-261126-0906）。
 *
 * <p>roles はコミットされるとクラスをまたいで残るため、本テストは {@code @Transactional} にせず、
 * 汚染行を実際に commit してから検証する。作ったユーザー・チーム・所属・権限ロールは最後に自分で削除する
 * （roles 行は正準値へ直った状態で残る。それが他の IT と共有する正しい状態）。</p>
 */
@DisplayName("RoleSeedSupport 回帰 IT")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RoleSeedSupportIT extends AbstractMySqlIntegrationTest {

    private static final String TEAM = "TEAM";

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private AccessControlService accessControl;

    @Test
    @DisplayName("commit 済みの誤った priority を正準値へ直し、hasRoleOrAbove が本番と同じ判定になる")
    void ensureCanonical_correctsCommittedPollutionAndAccessControlIsCorrect() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String slug = "rsit-" + UUID.randomUUID().toString().substring(0, 8);
        long base = 900_000_000L + ThreadLocalRandom.current().nextLong(50_000_000L) * 10;
        long supporter = base + 1;
        long member = base + 2;
        long deputy = base + 3;
        long admin = base + 4;
        long[] teamId = new long[1];
        try {
            // 1. 汚染行を commit する（全ロール同点の旧バグ形・ADMIN/DEPUTY_ADMIN の逆転形を含む）
            tx.executeWithoutResult(s -> {
                pollute("ADMIN", 100);
                pollute("DEPUTY_ADMIN", 80);
                pollute("MEMBER", 99);
                pollute("SUPPORTER", 99);
                pollute("GUEST", 99);
            });
            assertThat(priorityOf("MEMBER")).isEqualTo(99);

            // 2. ensureCanonical で全て正準値へ直す
            tx.executeWithoutResult(s -> {
                for (String name : new String[] {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST"}) {
                    RoleSeedSupport.ensureCanonical(em, name);
                }
            });
            for (String name : new String[] {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST"}) {
                assertThat(priorityOf(name)).as("priority of %s", name).isEqualTo(RolePriority.priority(name));
            }
            assertThat(priorityOf("DEPUTY_ADMIN")).isLessThan(priorityOf("MEMBER"));
            assertThat(priorityOf("MEMBER")).isLessThan(priorityOf("SUPPORTER"));

            // 3. 実際の認可判定（実 DB・実 AccessControlService）
            tx.executeWithoutResult(s -> {
                teamId[0] = TeamOrgFixtureHelper.insertTeam(em, "RoleSeedSupportIT " + slug, slug);
                for (long uid : new long[] {supporter, member, deputy, admin}) {
                    MembershipTestHelper.insertActiveUser(em, uid);
                }
                MembershipTestHelper.insertMembership(em, supporter, ScopeType.TEAM, teamId[0], RoleKind.SUPPORTER);
                MembershipTestHelper.insertMembership(em, member, ScopeType.TEAM, teamId[0], RoleKind.MEMBER);
                MembershipTestHelper.insertUserRole(em, deputy, "DEPUTY_ADMIN", teamId[0], null);
                MembershipTestHelper.insertUserRole(em, admin, "ADMIN", teamId[0], null);
            });

            long t = teamId[0];
            // SUPPORTER は MEMBER 以上と判定されない（全ロール同点の旧バグでは素通りした）
            assertThat(accessControl.hasRoleOrAbove(supporter, t, TEAM, "SUPPORTER")).isTrue();
            assertThat(accessControl.hasRoleOrAbove(supporter, t, TEAM, "MEMBER")).isFalse();
            assertThat(accessControl.hasRoleOrAbove(member, t, TEAM, "MEMBER")).isTrue();
            assertThat(accessControl.hasRoleOrAbove(member, t, TEAM, "SUPPORTER")).isTrue();
            assertThat(accessControl.hasRoleOrAbove(member, t, TEAM, "DEPUTY_ADMIN")).isFalse();
            // DEPUTY_ADMIN は ADMIN 未満
            assertThat(accessControl.hasRoleOrAbove(deputy, t, TEAM, "DEPUTY_ADMIN")).isTrue();
            assertThat(accessControl.hasRoleOrAbove(deputy, t, TEAM, "MEMBER")).isTrue();
            assertThat(accessControl.hasRoleOrAbove(deputy, t, TEAM, "ADMIN")).isFalse();
            assertThat(accessControl.hasRoleOrAbove(admin, t, TEAM, "ADMIN")).isTrue();
            assertThat(accessControl.hasRoleOrAbove(admin, t, TEAM, "DEPUTY_ADMIN")).isTrue();
        } finally {
            tx.executeWithoutResult(s -> cleanup(teamId[0], new long[] {supporter, member, deputy, admin}));
        }
    }

    private void cleanup(long teamId, long[] userIds) {
        for (long uid : userIds) {
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :u").setParameter("u", uid).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :u").setParameter("u", uid).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id = :u").setParameter("u", uid).executeUpdate();
        }
        if (teamId != 0) {
            em.createNativeQuery("DELETE FROM teams WHERE id = :t").setParameter("t", teamId).executeUpdate();
        }
    }

    /** 汚染の再現用。{@link RoleSeedSupport} を介さず直接書く（番人の KNOWN_VIOLATIONS 登録対象）。 */
    private void pollute(String name, int priority) {
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
