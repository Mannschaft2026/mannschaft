package com.mannschaft.app.common.visibility;

import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleProjection;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 戦役 Z-A PR4（試練）: {@link MembershipBatchQueryService#snapshotForUser} の SYSTEM_ADMIN 早期 return 撤去（AC5）。
 *
 * <p>SYSTEM_ADMIN でも SQL 2 本目以降を通常どおり発行し、所属が無ければ空の snapshot を返す。
 * {@code systemAdmin} 項目は existsSystemAdminByUserId の結果どおり true のまま（値は正しく計算する）。
 * SQL 本数の上限（最大 7）は {@code MembershipBatchQueryServiceIntegrationTest} で実測する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: MembershipBatchQueryService の SYSTEM_ADMIN 早期 return 撤去（AC5）")
class MembershipBatchQueryServiceSysAdminBypassRemovalTest {

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private ScopeAncestorResolver scopeAncestorResolver;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @InjectMocks private MembershipBatchQueryService service;

    private static final long SYSADMIN = 900L;
    private static final ScopeKey TEAM_1 = new ScopeKey("TEAM", 1L);
    private static final ScopeKey TEAM_2 = new ScopeKey("TEAM", 2L);

    @Test
    @DisplayName("所属の無い SYSTEM_ADMIN: 直接所属の SQL を発行し、systemAdmin=true・所属なしの snapshot を返す")
    void platformAccountWithoutAffiliation_queriesMembershipAndReturnsEmptyRoles() {
        when(userRoleRepository.existsSystemAdminByUserId(SYSADMIN)).thenReturn(1L);
        when(userRoleRepository.findByUserIdAndScopes(anyLong(), anySet(), anySet())).thenReturn(List.of());
        when(membershipRepository.findActiveRoleKindsByUserAndScopes(anyLong(), anySet(), anySet()))
                .thenReturn(List.of());
        when(scopeAncestorResolver.resolveParentOrgIds(anySet())).thenReturn(Map.of());

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(SYSADMIN, Set.of(TEAM_1), Set.of(TEAM_1));

        verify(userRoleRepository).findByUserIdAndScopes(eq(SYSADMIN), eq(Set.of(1L)), eq(Set.of()));
        assertThat(snapshot.isSystemAdmin()).as("値は正しく計算し続ける").isTrue();
        assertThat(snapshot.roleByScope()).isEmpty();
        assertThat(snapshot.isMemberOf(TEAM_1)).isFalse();
        assertThat(snapshot.hasRoleOrAbove(TEAM_1, "SUPPORTER")).isFalse();
        assertThat(snapshot.isMemberOfParentOrg(TEAM_1)).isFalse();
    }

    @Test
    @DisplayName("所属のある SYSTEM_ADMIN（移行前の兼任）は実ロールで判定され、所属外のスコープは false")
    void sysAdminWithAffiliation_usesRealRole() {
        when(userRoleRepository.existsSystemAdminByUserId(SYSADMIN)).thenReturn(1L);
        when(userRoleRepository.findByUserIdAndScopes(eq(SYSADMIN), any(), any()))
                .thenReturn(List.of(projection(1L, 1L, null, 50L)));
        when(roleRepository.findAllById(any())).thenReturn(List.of(role(50L, "MEMBER")));
        when(membershipRepository.findActiveRoleKindsByUserAndScopes(anyLong(), anySet(), anySet()))
                .thenReturn(List.of());

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(SYSADMIN, Set.of(TEAM_1, TEAM_2), Set.of());

        assertThat(snapshot.isSystemAdmin()).isTrue();
        assertThat(snapshot.roleByScope()).containsEntry(TEAM_1, "MEMBER");
        assertThat(snapshot.hasRoleOrAbove(TEAM_1, "MEMBER")).isTrue();
        assertThat(snapshot.hasRoleOrAbove(TEAM_1, "ADMIN")).isFalse();
        assertThat(snapshot.isMemberOf(TEAM_2)).isFalse();
    }

    @Test
    @DisplayName("回帰: 一般利用者は従来どおり systemAdmin=false・所属ロールを返す")
    void generalUser_regression() {
        when(userRoleRepository.existsSystemAdminByUserId(100L)).thenReturn(0L);
        when(userRoleRepository.findByUserIdAndScopes(eq(100L), any(), any()))
                .thenReturn(List.of(projection(1L, 1L, null, 51L)));
        when(roleRepository.findAllById(any())).thenReturn(List.of(role(51L, "ADMIN")));
        when(membershipRepository.findActiveRoleKindsByUserAndScopes(anyLong(), anySet(), anySet()))
                .thenReturn(List.of());

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(100L, Set.of(TEAM_1), Set.of());

        assertThat(snapshot.isSystemAdmin()).isFalse();
        assertThat(snapshot.hasRoleOrAbove(TEAM_1, "ADMIN")).isTrue();
        assertThat(snapshot.isMemberOf(TEAM_2)).isFalse();
    }

    private static UserRoleProjection projection(Long id, Long teamId, Long orgId, Long roleId) {
        return new UserRoleProjection() {
            @Override public Long getId() { return id; }
            @Override public Long getUserId() { return SYSADMIN; }
            @Override public Long getRoleId() { return roleId; }
            @Override public Long getTeamId() { return teamId; }
            @Override public Long getOrganizationId() { return orgId; }
        };
    }

    private static RoleEntity role(Long id, String name) {
        return RoleEntity.builder()
                .id(id)
                .name(name)
                .displayName(name)
                .priority(RolePriority.priority(name))
                .isSystem(false)
                .build();
    }
}
