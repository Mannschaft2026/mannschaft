package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.organization.service.OrganizationQueryService.OrganizationListItem;
import com.mannschaft.app.role.dto.MyOrganizationResponse;
import com.mannschaft.app.role.dto.MyTeamResponse;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamQueryService;
import com.mannschaft.app.team.service.TeamQueryService.TeamListItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 戦役 Z-A PR5 AC8: {@code GET /api/v1/me/teams}・{@code /api/v1/me/organizations} が
 * 実ロールを返し、強制的な "SYSTEM_ADMIN" 表示をしないことを固定する単体テスト。
 *
 * <p>マスター決定（2026-11-26）「ロール表示は実ロールのみ」。旧実装は
 * {@code roleSnapshot.isSystemAdmin() ? "SYSTEM_ADMIN" : ...} で所属チーム・組織のロールを上書きしていた。
 * 運営アカウントは所属禁止（PR2）だが、PR2 以前から所属を持つ兼任データ（点検 SQL の対象）でも
 * 実ロールを返すことを固定する。組織側の {@code canResolveRole} は {@code isActiveUser} だけで決まる
 * （SYSTEM_ADMIN であることは利用者状態の遮断を免除しない）。</p>
 */
@DisplayName("MeController 表示ロールの実ロール化（戦役Z-A PR5 AC8）")
class MeControllerRealRoleDisplayTest {

    private static final long USER_ID = 5501L;
    private static final long TEAM_ID = 7101L;
    private static final long ORG_ID = 8101L;

    private UserRoleRepository userRoles;
    private AccessControlService access;
    private TeamQueryService teamQuery;
    private TeamOrgMembershipQueryService teamOrg;
    private OrganizationQueryService orgQuery;
    private MembershipBatchQueryService batch;
    private MeController controller;

    @BeforeEach
    void setUp() {
        userRoles = mock(UserRoleRepository.class);
        access = mock(AccessControlService.class);
        teamQuery = mock(TeamQueryService.class);
        teamOrg = mock(TeamOrgMembershipQueryService.class);
        orgQuery = mock(OrganizationQueryService.class);
        batch = mock(MembershipBatchQueryService.class);
        controller = new MeController(userRoles, mock(RoleRepository.class), teamQuery, teamOrg, orgQuery,
                access, batch);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), "", List.of()));
        when(userRoles.findByUserIdAndTeamIdIsNotNull(USER_ID)).thenReturn(List.of());
        when(userRoles.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static UserScopeRoleSnapshot snapshot(boolean systemAdmin, ScopeKey scope, String role) {
        Map<ScopeKey, String> roles = scope == null ? Map.of() : Map.of(scope, role);
        return new UserScopeRoleSnapshot(systemAdmin, roles, Map.of(), Set.of(), Set.of());
    }

    /** memberships 由来でチーム 1 件に所属している状態を作る。 */
    private void givenTeamMembership(boolean systemAdmin, String role) {
        when(access.findActiveMembershipJoinedAtByScope(USER_ID, "TEAM"))
                .thenReturn(Map.of(TEAM_ID, LocalDateTime.of(2026, 1, 1, 0, 0)));
        when(teamQuery.findListItemsByIds(anySet())).thenReturn(Map.of(TEAM_ID,
                new TeamListItem(TEAM_ID, "team-a", "チームA", "MEMBERS_AND_ABOVE", null, null)));
        when(batch.snapshotForUser(eq(USER_ID), anySet(), eq(Set.of())))
                .thenReturn(snapshot(systemAdmin, new ScopeKey("TEAM", TEAM_ID), role));
    }

    /** memberships 由来で組織 1 件に所属している状態を作る。 */
    private void givenOrgMembership(boolean systemAdmin, String role, boolean activeUser) {
        when(access.findActiveMembershipJoinedAtByScope(USER_ID, "ORGANIZATION"))
                .thenReturn(Map.of(ORG_ID, LocalDateTime.of(2026, 1, 1, 0, 0)));
        when(orgQuery.findListItemsByIds(anySet())).thenReturn(Map.of(ORG_ID,
                new OrganizationListItem(ORG_ID, "org-a", "組織A", "PUBLIC", null)));
        when(batch.snapshotForUser(eq(USER_ID), anySet(), eq(Set.of())))
                .thenReturn(snapshot(systemAdmin, new ScopeKey("ORGANIZATION", ORG_ID), role));
        when(userRoles.isActiveUser(USER_ID)).thenReturn(activeUser);
    }

    private List<MyTeamResponse> teams() {
        return controller.getMyTeams(false, 50).getBody().getData();
    }

    private List<MyOrganizationResponse> organizations() {
        return controller.getMyOrganizations(false, 50).getBody().getData();
    }

    @Nested
    @DisplayName("/me/teams")
    class Teams {

        @ParameterizedTest(name = "SYSTEM_ADMIN 兼任でもチームの実ロール {0} を返す")
        @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER"})
        void systemAdmin兼任でも実ロールを返す(String role) {
            givenTeamMembership(true, role);

            List<MyTeamResponse> result = teams();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRole()).isEqualTo(role).isNotEqualTo("SYSTEM_ADMIN");
        }

        @ParameterizedTest(name = "一般利用者のチームロール {0} は従来どおり（回帰）")
        @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER"})
        void 一般利用者は従来どおり実ロール(String role) {
            givenTeamMembership(false, role);

            assertThat(teams()).extracting(MyTeamResponse::getRole).containsExactly(role);
        }

        @Test
        @DisplayName("所属の無い運営アカウントは空配列（SYSTEM_ADMIN の行を作らない）")
        void 所属なしの運営アカウントは空配列() {
            when(batch.snapshotForUser(eq(USER_ID), anySet(), eq(Set.of())))
                    .thenReturn(snapshot(true, null, null));

            assertThat(teams()).isEmpty();
        }
    }

    @Nested
    @DisplayName("/me/organizations")
    class Organizations {

        @ParameterizedTest(name = "SYSTEM_ADMIN 兼任でも組織の実ロール {0} を返す")
        @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER"})
        void systemAdmin兼任でも実ロールを返す(String role) {
            givenOrgMembership(true, role, true);

            List<MyOrganizationResponse> result = organizations();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRole()).isEqualTo(role).isNotEqualTo("SYSTEM_ADMIN");
        }

        @Test
        @DisplayName("canResolve は isActiveUser だけで決まる: 停止中の SYSTEM_ADMIN 兼任は ADMIN を解決せず MEMBER に落ちる")
        void canResolveはisActiveUserだけ() {
            givenOrgMembership(true, "ADMIN", false);

            assertThat(organizations()).extracting(MyOrganizationResponse::getRole).containsExactly("MEMBER");
        }

        @ParameterizedTest(name = "一般利用者の組織ロール {0} は従来どおり（回帰）")
        @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER"})
        void 一般利用者は従来どおり実ロール(String role) {
            givenOrgMembership(false, role, true);

            assertThat(organizations()).extracting(MyOrganizationResponse::getRole).containsExactly(role);
        }

        @Test
        @DisplayName("停止中の一般利用者は従来どおり MEMBER に落ちる（回帰）")
        void 停止中の一般利用者はMEMBER() {
            givenOrgMembership(false, "ADMIN", false);

            assertThat(organizations()).extracting(MyOrganizationResponse::getRole).containsExactly("MEMBER");
        }

        @Test
        @DisplayName("所属の無い運営アカウントは空配列")
        void 所属なしの運営アカウントは空配列() {
            when(batch.snapshotForUser(eq(USER_ID), anySet(), eq(Set.of())))
                    .thenReturn(snapshot(true, null, null));
            when(userRoles.isActiveUser(USER_ID)).thenReturn(true);

            assertThat(organizations()).isEmpty();
        }
    }
}
