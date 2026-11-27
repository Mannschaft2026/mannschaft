package com.mannschaft.app.common.sysadminbypass;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.family.service.FamilyCareLinkQuery;
import com.mannschaft.app.gdpr.service.GdprLastAdminWithdrawalErrorProvider;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.authorization.RoleScopeRoleQuery;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.DefaultAccessControlService;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-A PR3（試練）: {@code DefaultAccessControlService.resolveEffectiveRole} から SYSTEM_ADMIN の
 * 短絡（{@code EffectiveRole("SYSTEM_ADMIN", 1)}）を撤去する受け入れテスト。
 *
 * <ul>
 *   <li>AC3: 所属の無い SYSTEM_ADMIN では resolveEffectiveRoleName / getRoleName が null、hasRoleOrAbove が false。</li>
 *   <li>AC4: PLATFORM スコープは誰にとっても無権限（SYSTEM_ADMIN の {@code hasRoleOrAbove(0,"PLATFORM","MEMBER")}
 *       も false）。運営権限はロール階層に載せず {@code checkSystemAdmin} で判定するため、
 *       {@code checkAdminOrAboveIncludingPlatform} は SYSTEM_ADMIN だけを通し続ける（領収書の経路）。</li>
 *   <li>AC-兼任（回帰）: 所属のある一般利用者（ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER）の有効ロールは撤去前と同じ。</li>
 * </ul>
 *
 * <p>実体の {@link DefaultAccessControlService} を Repository のモックで組み立て、短絡の有無を直接観測する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR3: resolveEffectiveRole の SYSTEM_ADMIN 短絡撤去")
class EffectiveRoleSysAdminBypassRemovalTest {

    private static final Long SYSADMIN = 900L;
    private static final Long TEAM = 10L;
    private static final Long ORG = 20L;

    private static final Map<String, Integer> PRIORITY = Map.of(
            "SYSTEM_ADMIN", 1, "ADMIN", 2, "DEPUTY_ADMIN", 3, "MEMBER", 4, "SUPPORTER", 5, "GUEST", 6);

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private UserCareLinkRepository userCareLinkRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private OrganizationMembershipService organizationMembershipService;

    private AccessControlService acs;

    @BeforeEach
    void setUp() {
        acs = new DefaultAccessControlService(
                userRoleRepository, roleRepository, roleService, membershipRepository, null,
                organizationMembershipService, new RoleScopeRoleQuery(userRoleRepository),
                List.of(new FamilyCareLinkQuery(userCareLinkRepository)),
                List.of(new GdprLastAdminWithdrawalErrorProvider()));
        PRIORITY.forEach((name, priority) ->
                given(roleRepository.findByName(name)).willReturn(Optional.of(role(priority, name, priority))));
        PRIORITY.forEach((name, priority) ->
                given(roleRepository.findById((long) priority)).willReturn(Optional.of(role(priority, name, priority))));
        // 既定: 誰も SYSTEM_ADMIN ではなく、どのスコープにも所属しない。
        given(userRoleRepository.existsSystemAdminByUserId(anyLong())).willReturn(0L);
        given(userRoleRepository.isActiveUser(anyLong())).willReturn(true);
        given(userRoleRepository.findByUserIdAndTeamId(anyLong(), anyLong())).willReturn(Optional.empty());
        given(userRoleRepository.findByUserIdAndOrganizationId(anyLong(), anyLong())).willReturn(Optional.empty());
        given(membershipRepository.findActiveRoleKinds(anyLong(), org.mockito.ArgumentMatchers.any(ScopeType.class),
                anyLong())).willReturn(List.of());
        // 運営アカウント: プラットフォームロール SYSTEM_ADMIN のみ（team_id / organization_id が null の行）。
        given(userRoleRepository.existsSystemAdminByUserId(SYSADMIN)).willReturn(1L);
        given(userRoleRepository.isSystemAdmin(SYSADMIN)).willReturn(1L);
    }

    private static RoleEntity role(long id, String name, int priority) {
        return RoleEntity.builder().id(id).name(name).displayName(name).priority(priority).isSystem(true).build();
    }

    private void grantUserRole(Long userId, Long teamId, String roleName) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .id(userId * 100).userId(userId).roleId((long) PRIORITY.get(roleName)).teamId(teamId).build();
        given(userRoleRepository.findByUserIdAndTeamId(userId, teamId)).willReturn(Optional.of(ur));
    }

    private void grantMembership(Long userId, Long teamId, RoleKind kind) {
        given(membershipRepository.findActiveRoleKinds(userId, ScopeType.TEAM, teamId)).willReturn(List.of(kind));
        given(membershipRepository.existsActiveByUserAndScope(userId, ScopeType.TEAM, teamId)).willReturn(true);
    }

    @Nested
    @DisplayName("AC3: 所属の無い SYSTEM_ADMIN はスコープ内ロールを持たない")
    class SystemAdminHasNoScopeRole {

        @Test
        @DisplayName("resolveEffectiveRoleName は TEAM / ORGANIZATION とも null")
        void resolveEffectiveRoleName_null() {
            assertThat(acs.resolveEffectiveRoleName(SYSADMIN, TEAM, "TEAM")).isNull();
            assertThat(acs.resolveEffectiveRoleName(SYSADMIN, ORG, "ORGANIZATION")).isNull();
        }

        @Test
        @DisplayName("getRoleName は TEAM / ORGANIZATION とも null（\"SYSTEM_ADMIN\" を返さない）")
        void getRoleName_null() {
            assertThat(acs.getRoleName(SYSADMIN, TEAM, "TEAM")).isNull();
            assertThat(acs.getRoleName(SYSADMIN, ORG, "ORGANIZATION")).isNull();
        }

        @Test
        @DisplayName("hasRoleOrAbove はどのロール閾値でも false")
        void hasRoleOrAbove_false() {
            for (String required : List.of("ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST")) {
                assertThat(acs.hasRoleOrAbove(SYSADMIN, TEAM, "TEAM", required))
                        .as("TEAM で %s 以上", required).isFalse();
                assertThat(acs.hasRoleOrAbove(SYSADMIN, ORG, "ORGANIZATION", required))
                        .as("ORGANIZATION で %s 以上", required).isFalse();
            }
        }

        @Test
        @DisplayName("SYSTEM_ADMIN 自体の判定（isSystemAdmin / checkSystemAdmin）は変わらない")
        void platformRoleItselfUnchanged() {
            assertThat(acs.isSystemAdmin(SYSADMIN)).isTrue();
            assertThatCode(() -> acs.checkSystemAdmin(SYSADMIN)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AC4: PLATFORM は全員が無権限、運営は checkSystemAdmin でのみ通る")
    class PlatformScope {

        @Test
        @DisplayName("SYSTEM_ADMIN の hasRoleOrAbove(0, \"PLATFORM\", \"MEMBER\") は false")
        void systemAdminHasNoPlatformRole() {
            assertThat(acs.hasRoleOrAbove(SYSADMIN, 0L, AccessControlService.PLATFORM_SCOPE_TYPE, "MEMBER")).isFalse();
            assertThat(acs.hasRoleOrAbove(SYSADMIN, 0L, AccessControlService.PLATFORM_SCOPE_TYPE, "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN の getRoleName / resolveEffectiveRoleName(PLATFORM) は null")
        void systemAdminPlatformRoleNameIsNull() {
            assertThat(acs.getRoleName(SYSADMIN, 0L, AccessControlService.PLATFORM_SCOPE_TYPE)).isNull();
            assertThat(acs.resolveEffectiveRoleName(SYSADMIN, 0L, AccessControlService.PLATFORM_SCOPE_TYPE)).isNull();
        }

        @Test
        @DisplayName("一般利用者も PLATFORM では無権限（false / null、500 にならない）")
        void ordinaryUserHasNoPlatformRole() {
            assertThat(acs.hasRoleOrAbove(11L, 0L, AccessControlService.PLATFORM_SCOPE_TYPE, "MEMBER")).isFalse();
            assertThat(acs.getRoleName(11L, 0L, AccessControlService.PLATFORM_SCOPE_TYPE)).isNull();
        }

        @Test
        @DisplayName("checkAdminOrAboveIncludingPlatform(PLATFORM) は SYSTEM_ADMIN を通す（運営の領収書 API は通り続ける）")
        void platformReceiptPathStillAllowsSystemAdmin() {
            assertThatCode(() -> acs.checkAdminOrAboveIncludingPlatform(
                    SYSADMIN, 0L, AccessControlService.PLATFORM_SCOPE_TYPE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("checkAdminOrAboveIncludingPlatform(PLATFORM) は SYSTEM_ADMIN 以外を 403 COMMON_002")
        void platformReceiptPathRejectsOthers() {
            grantUserRole(11L, TEAM, "ADMIN");
            assertThatThrownBy(() -> acs.checkAdminOrAboveIncludingPlatform(
                    11L, 0L, AccessControlService.PLATFORM_SCOPE_TYPE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(((BusinessException) t).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("AC-兼任（回帰）: 所属のある一般利用者の有効ロールは撤去前と同じ")
    class AffiliatedUsersUnchanged {

        @Test
        @DisplayName("ADMIN（user_roles）: ロール名 ADMIN、DEPUTY_ADMIN 以上・ADMIN 以上が true")
        void admin() {
            grantUserRole(11L, TEAM, "ADMIN");
            grantMembership(11L, TEAM, RoleKind.MEMBER);
            assertThat(acs.resolveEffectiveRoleName(11L, TEAM, "TEAM")).isEqualTo("ADMIN");
            assertThat(acs.getRoleName(11L, TEAM, "TEAM")).isEqualTo("ADMIN");
            assertThat(acs.hasRoleOrAbove(11L, TEAM, "TEAM", "ADMIN")).isTrue();
            assertThat(acs.hasRoleOrAbove(11L, TEAM, "TEAM", "MEMBER")).isTrue();
            assertThat(acs.hasRoleOrAbove(11L, TEAM, "TEAM", "SYSTEM_ADMIN")).isFalse();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN: ロール名 DEPUTY_ADMIN、DEPUTY_ADMIN 以上 true・ADMIN 以上 false")
        void deputy() {
            grantUserRole(12L, TEAM, "DEPUTY_ADMIN");
            grantMembership(12L, TEAM, RoleKind.MEMBER);
            assertThat(acs.resolveEffectiveRoleName(12L, TEAM, "TEAM")).isEqualTo("DEPUTY_ADMIN");
            assertThat(acs.hasRoleOrAbove(12L, TEAM, "TEAM", "DEPUTY_ADMIN")).isTrue();
            assertThat(acs.hasRoleOrAbove(12L, TEAM, "TEAM", "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("MEMBER（memberships）: ロール名 MEMBER、MEMBER 以上 true・DEPUTY_ADMIN 以上 false")
        void member() {
            grantMembership(13L, TEAM, RoleKind.MEMBER);
            assertThat(acs.resolveEffectiveRoleName(13L, TEAM, "TEAM")).isEqualTo("MEMBER");
            assertThat(acs.hasRoleOrAbove(13L, TEAM, "TEAM", "MEMBER")).isTrue();
            assertThat(acs.hasRoleOrAbove(13L, TEAM, "TEAM", "DEPUTY_ADMIN")).isFalse();
        }

        @Test
        @DisplayName("SUPPORTER（memberships）: ロール名 SUPPORTER、SUPPORTER 以上 true・MEMBER 以上 false")
        void supporter() {
            grantMembership(14L, TEAM, RoleKind.SUPPORTER);
            assertThat(acs.resolveEffectiveRoleName(14L, TEAM, "TEAM")).isEqualTo("SUPPORTER");
            assertThat(acs.hasRoleOrAbove(14L, TEAM, "TEAM", "SUPPORTER")).isTrue();
            assertThat(acs.hasRoleOrAbove(14L, TEAM, "TEAM", "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("所属の無い一般利用者: null / false（撤去前と同じ）")
        void outsider() {
            assertThat(acs.resolveEffectiveRoleName(15L, TEAM, "TEAM")).isNull();
            assertThat(acs.hasRoleOrAbove(15L, TEAM, "TEAM", "SUPPORTER")).isFalse();
        }
    }
}
