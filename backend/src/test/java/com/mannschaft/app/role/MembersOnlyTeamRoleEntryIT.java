package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import com.mannschaft.app.team.TeamErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * CMP-261124-0159 PR-E: 入口の関所（ロール変更・割当・在籍の作成）の実 MySQL 結合テスト（AC-0159-46 / 48）。
 *
 * <p>F01.2 03「入口の関所」。非公開チーム（{@code MEMBERS_AND_ABOVE}）では SUPPORTER 在籍・GUEST 付与を作れず
 * 403 {@code TEAM_073}、在籍・{@code user_roles} は不変。ADMIN / DEPUTY_ADMIN / MEMBER の間の変更は従来どおり。
 * 公開チームの期待値は本戦役前（origin/main）の挙動を固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 入口の関所（ロール変更・割当・在籍の作成。AC-0159-46/48）")
class MembersOnlyTeamRoleEntryIT extends AbstractMySqlIntegrationTest {

    @Autowired private RoleService roleService;
    @Autowired private MembershipService membershipService;
    @Autowired private MembershipGrantService membershipGrantService;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private ScopeContentFixture fixture;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        fixture = tx.execute(t -> ScopeContentFixture.create(em));
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(t -> fixture.delete(em));
    }

    private void setVisibility(String visibility) {
        tx.executeWithoutResult(t -> ScopeContentFixture.setTeamVisibility(em, fixture.teamId, visibility));
    }

    private Long roleId(String name) {
        return roleRepository.findByName(name).orElseThrow().getId();
    }

    private Long user(Actor actor) {
        return fixture.user(actor);
    }

    /** 対象者の当該チームでの状態（在籍の role_kind 一覧・user_roles のロール名一覧）。 */
    private Map<String, List<String>> snapshot(Long userId) {
        return tx.execute(t -> Map.of(
                "memberships", stringList(em.createNativeQuery("SELECT role_kind FROM memberships WHERE user_id = :u "
                        + "AND scope_type = 'TEAM' AND scope_id = :t AND left_at IS NULL ORDER BY id")
                        .setParameter("u", userId).setParameter("t", fixture.teamId).getResultList()),
                "userRoles", stringList(em.createNativeQuery("SELECT r.name FROM user_roles ur JOIN roles r "
                        + "ON r.id = ur.role_id WHERE ur.user_id = :u AND ur.team_id = :t ORDER BY ur.id")
                        .setParameter("u", userId).setParameter("t", fixture.teamId).getResultList())));
    }

    private static List<String> stringList(List<?> rows) {
        return rows.stream().map(String::valueOf).toList();
    }

    private static BusinessException business(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e;
        }
        return fail("BusinessException が投げられなかった");
    }

    // ───────── PATCH /teams/{slug}/members/{userId}/role（RoleService#changeRole） ─────────

    @Test
    @DisplayName("AC-0159-48: 非公開チームで ADMIN が MEMBER を SUPPORTER へ降格すると 403 TEAM_073・在籍と user_roles は不変")
    void 非公開チームでSUPPORTERへの降格は拒否() {
        Long member = user(Actor.MEMBER);
        Map<String, List<String>> before = snapshot(member);

        BusinessException e = business(() -> roleService.changeRole(fixture.teamId, "TEAM", member,
                new RoleChangeRequest(roleId("SUPPORTER")), user(Actor.ADMIN)));

        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        assertThat(e.getHttpStatusOverride()).as("既定の 403（ERROR_CODE_STATUS_MAP）").isNull();
        assertThat(snapshot(member)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-0159-48: 非公開チームで ADMIN が非所属者に GUEST を付与すると（変更・割当とも）403 TEAM_073・何も作られない")
    void 非公開チームでGUEST付与は拒否() {
        Long outsider = user(Actor.OUTSIDER);

        BusinessException byChange = business(() -> roleService.changeRole(fixture.teamId, "TEAM", outsider,
                new RoleChangeRequest(roleId("GUEST")), user(Actor.ADMIN)));
        BusinessException byAssign = business(() -> roleService.assignRole(fixture.teamId, "TEAM", outsider,
                roleId("GUEST"), user(Actor.ADMIN)));

        assertThat(byChange.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        assertThat(byAssign.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        assertThat(snapshot(outsider)).isEqualTo(Map.of("memberships", List.of(), "userRoles", List.of()));
    }

    @Test
    @DisplayName("AC-0159-48: 非公開チームでも ADMIN / DEPUTY_ADMIN / MEMBER の間の変更は従来どおり成功する")
    void 非公開チームでも正規ロール間の変更は通る() {
        Long deputy = user(Actor.DEPUTY_ADMIN);
        Long admin = user(Actor.ADMIN);

        assertThatCode(() -> roleService.changeRole(fixture.teamId, "TEAM", deputy,
                new RoleChangeRequest(roleId("MEMBER")), admin)).doesNotThrowAnyException();
        assertThat(snapshot(deputy).get("userRoles")).containsExactly("MEMBER");

        assertThatCode(() -> roleService.changeRole(fixture.teamId, "TEAM", deputy,
                new RoleChangeRequest(roleId("DEPUTY_ADMIN")), admin)).doesNotThrowAnyException();
        assertThat(snapshot(deputy).get("userRoles")).containsExactly("DEPUTY_ADMIN");

        assertThatCode(() -> roleService.changeRole(fixture.teamId, "TEAM", deputy,
                new RoleChangeRequest(roleId("ADMIN")), admin)).doesNotThrowAnyException();
        assertThat(snapshot(deputy).get("userRoles")).containsExactly("ADMIN");
        assertThat(snapshot(deputy).get("memberships")).containsExactly("MEMBER");
    }

    @Test
    @DisplayName("AC-0159-48: 権限の無い操作者（MEMBER・サポーター・部外者）には TEAM_073 でなく従来の拒否（公開チームと同一）")
    void 権限なしは従来の拒否() {
        Long target = user(Actor.MEMBER);
        for (Actor operator : List.of(Actor.MEMBER, Actor.SUPPORTER, Actor.OUTSIDER, Actor.OTHER_TEAM_MEMBER)) {
            setVisibility("SUPPORTERS_AND_ABOVE");
            BusinessException publicDenial = business(() -> roleService.changeRole(fixture.teamId, "TEAM", target,
                    new RoleChangeRequest(roleId("SUPPORTER")), user(operator)));
            setVisibility("MEMBERS_AND_ABOVE");
            BusinessException privateDenial = business(() -> roleService.changeRole(fixture.teamId, "TEAM", target,
                    new RoleChangeRequest(roleId("SUPPORTER")), user(operator)));

            assertThat(privateDenial.getErrorCode()).as("operator=%s", operator)
                    .isNotEqualTo(TeamErrorCode.TEAM_073)
                    .isEqualTo(publicDenial.getErrorCode());
            assertThat(privateDenial.getHttpStatusOverride()).isEqualTo(publicDenial.getHttpStatusOverride());
        }
    }

    @Test
    @DisplayName("AC-0159-46: 公開チームでは GUEST の割当が従来どおり成功する（本戦役前と同一）")
    void 公開チームのGUEST割当は従来どおり() {
        setVisibility("SUPPORTERS_AND_ABOVE");
        Long outsider = user(Actor.OUTSIDER);

        assertThatCode(() -> roleService.assignRole(fixture.teamId, "TEAM", outsider, roleId("GUEST"),
                user(Actor.ADMIN))).doesNotThrowAnyException();

        assertThat(snapshot(outsider).get("userRoles")).containsExactly("GUEST");
    }

    // ───────── 関所 MembershipService#join（参加申請の承認・組織経由の付与もここを通る） ─────────

    @Test
    @DisplayName("AC-0159-48: 関所 MembershipService#join は非公開チームの SUPPORTER 在籍を作らず 403 TEAM_073。MEMBER の在籍は作れる")
    void 関所joinは非公開チームのSUPPORTERを作らない() {
        Long outsider = user(Actor.OUTSIDER);
        MembershipCreateRequest supporter = joinRequest(outsider, RoleKind.SUPPORTER);

        BusinessException e = business(() -> tx.executeWithoutResult(t -> membershipService.join(supporter)));

        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        assertThat(snapshot(outsider).get("memberships")).isEmpty();

        tx.executeWithoutResult(t -> membershipService.join(joinRequest(outsider, RoleKind.MEMBER)));
        assertThat(snapshot(outsider).get("memberships")).containsExactly("MEMBER");
    }

    @Test
    @DisplayName("AC-0159-48: 参加申請の承認（MEMBER 付与）は非公開チームでも従来どおり在籍を作る")
    void 参加申請の承認は非公開チームでも通る() {
        Long outsider = user(Actor.OUTSIDER);

        membershipGrantService.grantMemberRole("TEAM", fixture.teamId, outsider, user(Actor.ADMIN), "JOIN_REQUEST");

        assertThat(snapshot(outsider).get("memberships")).containsExactly("MEMBER");
    }

    @Test
    @DisplayName("AC-0159-46: 公開チームでは関所 join で SUPPORTER 在籍を作れる（本戦役前と同一）")
    void 公開チームの関所joinは従来どおり() {
        setVisibility("SUPPORTERS_AND_ABOVE");
        Long outsider = user(Actor.OUTSIDER);

        tx.executeWithoutResult(t -> membershipService.join(joinRequest(outsider, RoleKind.SUPPORTER)));

        assertThat(snapshot(outsider).get("memberships")).containsExactly("SUPPORTER");
    }

    private MembershipCreateRequest joinRequest(Long userId, RoleKind roleKind) {
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType(ScopeType.TEAM);
        req.setScopeId(fixture.teamId);
        req.setRoleKind(roleKind);
        req.setSource("CMP_0159_IT");
        return req;
    }
}
