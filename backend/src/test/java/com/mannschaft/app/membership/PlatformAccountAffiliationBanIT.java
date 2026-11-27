package com.mannschaft.app.membership;

import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.service.RoleSuccessionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.mannschaft.app.team.service.TeamService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 戦役 Z-A PR2 結合テスト（試練）: 実 DB で、運営アカウント（SYSTEM_ADMIN）がテナントの所属行を
 * 1 件も得られないこと（P1）、一般利用者は従来どおり（P4）、プロビジョニング作成は対象外（P3）を固定する。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("運営アカウントのテナント所属禁止 結合テスト（戦役Z-A PR2）")
class PlatformAccountAffiliationBanIT extends AbstractMySqlIntegrationTest {

    private static final Long PLATFORM_USER_ID = 9_260_001L;
    private static final Long GENERAL_USER_ID = 9_260_002L;

    /** 運営アカウントの判定（取引の外）。本番の Controller と同じく判定してから呼ぶ（戦役 Z-A PR2・D-3T）。 */
    @Autowired
    private PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private RoleSuccessionService roleSuccessionService;

    @Autowired
    private TeamService teamService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, PLATFORM_USER_ID);
        MembershipTestHelper.insertActiveUser(em, GENERAL_USER_ID);
        MembershipTestHelper.insertUserRole(em, PLATFORM_USER_ID, "SYSTEM_ADMIN", null, null);
        teamId = TeamOrgFixtureHelper.insertTeam(em, "Z-A 所属禁止チーム", "z-a-affiliation-ban");
        em.flush();
    }

    private MembershipCreateRequest join(Long userId, RoleKind roleKind) {
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType(ScopeType.TEAM);
        req.setScopeId(teamId);
        req.setRoleKind(roleKind);
        req.setSource("IT");
        return req;
    }

    private long countMemberships(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM memberships WHERE user_id = :uid AND left_at IS NULL")
                .setParameter("uid", userId).getSingleResult()).longValue();
    }

    private long countScopedUserRoles(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_roles WHERE user_id = :uid "
                                + "AND (team_id IS NOT NULL OR organization_id IS NOT NULL)")
                .setParameter("uid", userId).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("P1: 運営アカウントのチーム入会（MEMBER）は 403 で、memberships を 1 件も作らない")
    void P1_運営アカウントはチームに入会できない() {
        assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.callJudged(platformAccountAffiliationPolicy.judge(java.util.List.of(PLATFORM_USER_ID)),
                () -> membershipService.join(join(PLATFORM_USER_ID, RoleKind.MEMBER))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        em.flush();
        assertThat(countMemberships(PLATFORM_USER_ID)).isZero();
    }

    @Test
    @DisplayName("P1: 運営アカウントのサポーター（フォロー）登録は 403 で、memberships を 1 件も作らない")
    void P1_運営アカウントはサポーターになれない() {
        assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.callJudged(platformAccountAffiliationPolicy.judge(java.util.List.of(PLATFORM_USER_ID)),
                () -> membershipService.join(join(PLATFORM_USER_ID, RoleKind.SUPPORTER))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        em.flush();
        assertThat(countMemberships(PLATFORM_USER_ID)).isZero();
    }

    @Test
    @DisplayName("P4: 一般利用者のチーム入会は従来どおり成功する（回帰）")
    void P4_一般利用者は従来どおり入会できる() {
        PlatformAccountAffiliationPolicy.callJudged(platformAccountAffiliationPolicy.judge(java.util.List.of(GENERAL_USER_ID)),
                () -> membershipService.join(join(GENERAL_USER_ID, RoleKind.MEMBER)));
        em.flush();
        assertThat(countMemberships(GENERAL_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("P1: 後継候補の選出は、在籍の残った運営アカウントを飛ばして一般利用者を選ぶ")
    void P1_後継候補の選出から運営アカウントを除外する() {
        // 既存の誤所属（PR2 以前に作られた行）を想定し、運営アカウントを先に在籍させておく。
        MembershipTestHelper.insertMembership(em, PLATFORM_USER_ID, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, GENERAL_USER_ID, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.flush();

        Optional<Long> candidate = roleSuccessionService.selectSuccessionCandidate(teamId, "TEAM");

        assertThat(candidate).contains(GENERAL_USER_ID);
    }

    @Test
    @DisplayName("P3: 運営によるプロビジョニング作成は従来どおり成功し、運営アカウントの所属行を作らない")
    void P3_プロビジョニング作成は成功し所属行を作らない() {
        Long provisionedTeamId = teamService.createProvisionedTeam(
                "Z-A 販促チーム", "z-a-provisioned-team", PLATFORM_USER_ID, false, null);
        em.flush();

        assertThat(provisionedTeamId).isNotNull();
        assertThat(countMemberships(PLATFORM_USER_ID)).isZero();
        assertThat(countScopedUserRoles(PLATFORM_USER_ID)).isZero();
    }
}
