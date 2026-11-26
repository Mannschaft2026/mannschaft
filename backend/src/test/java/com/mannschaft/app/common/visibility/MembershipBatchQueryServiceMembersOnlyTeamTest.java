package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.MembersOnlyTeamQuery;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.MembershipScopeRoleProjection;
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
import static org.mockito.ArgumentMatchers.eq;
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
    @Test
    @DisplayName("F00 §20.7: 閲覧者が既に MEMBER 以上のチームは上限が発動し得ないため問い合わせに載せない（在籍メンバーの閲覧で本数不変）")
    void MEMBER以上のチームは問い合わせに載せない() {
        when(userRoleRepository.existsSystemAdminByUserId(5L)).thenReturn(0L);
        when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(5L), anySet(), anySet()))
                .thenReturn(List.of(membership(1L, RoleKind.MEMBER), membership(2L, RoleKind.SUPPORTER)));
        when(membersOnlyTeamQuery.findMembersOnlyTeamIds(anyCollection())).thenReturn(Set.of(2L));

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(5L, Set.of(TEAM_1, TEAM_2), Set.of());

        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_1)).isFalse();
        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_2)).isTrue();
        verify(membersOnlyTeamQuery, times(1)).findMembersOnlyTeamIds(argThat(ids -> ids.size() == 1 && ids.contains(2L)));
    }

    @Test
    @DisplayName("F00 §20.7: direct の TEAM すべてで MEMBER 以上なら非公開チームの問い合わせを 1 本も撃たない")
    void 全チームMEMBER以上なら撃たない() {
        when(userRoleRepository.existsSystemAdminByUserId(5L)).thenReturn(0L);
        when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(5L), anySet(), anySet()))
                .thenReturn(List.of(membership(1L, RoleKind.MEMBER)));

        UserScopeRoleSnapshot snapshot = service.snapshotForUser(5L, Set.of(TEAM_1), Set.of());

        assertThat(snapshot.isBeyondMembersOnlyTeamCap(TEAM_1)).isFalse();
        verify(membersOnlyTeamQuery, never()).findMembersOnlyTeamIds(anyCollection());
    }

    @Test
    @DisplayName("委任役割で MEMBER 未満へ差し替えたチームは、本人が MEMBER 以上で未確認だった分だけ引き直して上限を課す")
    void 委任役割の差し替えで未確認チームを引き直す() {
        UserScopeRoleSnapshot own = new UserScopeRoleSnapshot(false, java.util.Map.of(TEAM_1, "MEMBER"),
                null, null, null, null, null, null);
        UserScopeRoleSnapshot replaced = new UserScopeRoleSnapshot(false, java.util.Map.of(TEAM_1, "SUPPORTER"),
                null, null, null, null, null, null);
        when(membersOnlyTeamQuery.findMembersOnlyTeamIds(anyCollection())).thenReturn(Set.of(1L));

        UserScopeRoleSnapshot result =
                service.supplementMembersOnlyTeamCapForRoleOverride(own, replaced, TEAM_1, "SUPPORTER");

        assertThat(result.isBeyondMembersOnlyTeamCap(TEAM_1)).isTrue();
        verify(membersOnlyTeamQuery, times(1)).findMembersOnlyTeamIds(List.of(1L));
    }

    @Test
    @DisplayName("委任役割が MEMBER 以上、または本人も MEMBER 未満（問い合わせ済み）なら引き直さない")
    void 委任役割の差し替えで引き直さない場合() {
        UserScopeRoleSnapshot own = new UserScopeRoleSnapshot(false, java.util.Map.of(TEAM_1, "MEMBER"),
                null, null, null, null, null, null);
        UserScopeRoleSnapshot asMember = new UserScopeRoleSnapshot(false, java.util.Map.of(TEAM_1, "MEMBER"),
                null, null, null, null, null, null);
        service.supplementMembersOnlyTeamCapForRoleOverride(own, asMember, TEAM_1, "MEMBER");

        UserScopeRoleSnapshot ownSupporter = new UserScopeRoleSnapshot(false, java.util.Map.of(TEAM_1, "SUPPORTER"),
                null, null, null, null, null, null).withMembersOnlyTeamIds(Set.of(1L));
        UserScopeRoleSnapshot result = service.supplementMembersOnlyTeamCapForRoleOverride(
                ownSupporter, ownSupporter, TEAM_1, "SUPPORTER");

        assertThat(result.isBeyondMembersOnlyTeamCap(TEAM_1)).isTrue();
        verify(membersOnlyTeamQuery, never()).findMembersOnlyTeamIds(anyCollection());
    }

    private static MembershipScopeRoleProjection membership(Long teamId, RoleKind roleKind) {
        return new MembershipScopeRoleProjection() {
            @Override
            public ScopeType getScopeType() {
                return ScopeType.TEAM;
            }

            @Override
            public Long getScopeId() {
                return teamId;
            }

            @Override
            public RoleKind getRoleKind() {
                return roleKind;
            }
        };
    }
}
