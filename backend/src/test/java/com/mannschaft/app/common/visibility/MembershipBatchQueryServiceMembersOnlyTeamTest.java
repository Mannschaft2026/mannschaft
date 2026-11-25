package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.MembersOnlyTeamQuery;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.repository.RoleRepository;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-261124-0159 PR-01 試練: {@link MembershipBatchQueryService} が判定対象の所属チームのうち非公開チームの
 * ID 集合を 1 本の問い合わせで引き、スナップショットに積むこと（F00 §20.2 規則 4/5・AC-0159-01/03/07）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261124-0159 スナップショットの非公開チーム集合")
class MembershipBatchQueryServiceMembersOnlyTeamTest {

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private ScopeAncestorResolver scopeAncestorResolver;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private MembersOnlyTeamQuery membersOnlyTeamQuery;

    @InjectMocks private MembershipBatchQueryService service;

    private static final ScopeKey TEAM_1 = new ScopeKey("TEAM", 1L);
    private static final ScopeKey TEAM_2 = new ScopeKey("TEAM", 2L);
    private static final ScopeKey ORG_10 = new ScopeKey("ORGANIZATION", 10L);

    @Test
    @DisplayName("一般ユーザー: direct の TEAM だけを 1 回の問い合わせに載せ、結果をスナップショットに積む")
    void 一般ユーザー() {
        when(userRoleRepository.existsSystemAdminByUserId(5L)).thenReturn(0L);
        when(membersOnlyTeamQuery.findMembersOnlyTeamIds(anyCollection())).thenReturn(Set.of(2L));

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(5L, Set.of(TEAM_1, TEAM_2, ORG_10), Set.of());

        assertThat(snapshot.membersOnlyTeamIds()).containsExactly(2L);
        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_2)).isTrue();
        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_1)).isFalse();
        verify(membersOnlyTeamQuery, times(1)).findMembersOnlyTeamIds(
                argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(1L, 2L))));
    }

    @Test
    @DisplayName("未ログイン: 非公開チームの PUBLIC コンテンツも隠すため、未ログインでも非公開チーム集合だけは引く（§20.2 規則 8）")
    void 未ログイン() {
        when(membersOnlyTeamQuery.findMembersOnlyTeamIds(anyCollection())).thenReturn(Set.of(1L));

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(null, Set.of(TEAM_1), Set.of());

        assertThat(snapshot.membersOnlyTeamIds()).containsExactly(1L);
        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_1)).isTrue();
        verify(userRoleRepository, never()).existsSystemAdminByUserId(anyLong());
        verify(userRoleRepository, never()).findByUserIdAndScopes(anyLong(), anySet(), anySet());
    }

    @Test
    @DisplayName("AC-0159-07: SystemAdmin は上限を課さないため非公開チーム集合を引かない（問い合わせ本数も不変）")
    void システム管理者() {
        when(userRoleRepository.existsSystemAdminByUserId(5L)).thenReturn(1L);

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(5L, Set.of(TEAM_1), Set.of());

        assertThat(snapshot.isSystemAdmin()).isTrue();
        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_1)).isFalse();
        verify(membersOnlyTeamQuery, never()).findMembersOnlyTeamIds(anyCollection());
    }

    @Test
    @DisplayName("TEAM を含まない判定（組織のみ）では問い合わせを撃たない（本数不変）")
    void 組織だけなら撃たない() {
        when(userRoleRepository.existsSystemAdminByUserId(5L)).thenReturn(0L);

        service.snapshotForUser(5L, Set.of(ORG_10), Set.of());
        service.snapshotForUser(null, Set.of(ORG_10), Set.of());

        verify(membersOnlyTeamQuery, never()).findMembersOnlyTeamIds(anyCollection());
    }
}
