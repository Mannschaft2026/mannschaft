package com.mannschaft.app.common;

import com.mannschaft.app.family.service.FamilyCareLinkQuery;
import com.mannschaft.app.gdpr.service.GdprLastAdminWithdrawalErrorProvider;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.authorization.RoleScopeRoleQuery;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.DefaultAccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CMP-261124-0159 PR-01 試練: 共通の門 {@link ScopeContentAccessGate#checkScopeContentAccess} の真偽表
 * （F00 §20.3・AC-0159-04 / 05 / 07 / 09）。
 *
 * <p>チームの公開範囲 4 段 × 閲覧者 8 区分＋未ログインの全組合せで、
 * 非公開チーム（MEMBERS_AND_ABOVE）では MEMBER / DEPUTY_ADMIN / ADMIN だけを通し、
 * それ以外を {@link AccessControlService#checkMembership} の部外者拒否と同一の 403 COMMON_002 で拒否する。
 * 公開側の 3 段では {@link AccessControlService#checkMembership} と全件一致する（上限を足さない）。
 * {@code checkMembership} 自身の結果は本戦役前と全件一致する（書き込み経路の回帰）。</p>
 *
 * <p>実 {@link DefaultAccessControlService} にリポジトリのモックを差して判定ロジックそのものを検証する
 * （実 MySQL での等価性は {@code ScopeContentAccessGateEquivalenceTest}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261124-0159 共通の門の真偽表（AC-0159-04/05/07/09）")
class ScopeContentAccessGateTruthTableTest {

    private static final Long TEAM_ID = 501L;
    private static final Long ORG_ID = 601L;

    /** 閲覧者 8 区分＋未ログイン。 */
    enum Viewer {
        ADMIN(1L, true, true),
        DEPUTY_ADMIN(2L, true, true),
        MEMBER(3L, true, true),
        SUPPORTER(4L, true, false),
        GUEST(5L, false, false),
        PARENT_ORG_ADMIN(6L, false, false),
        OUTSIDER(7L, false, false),
        SYSTEM_ADMIN(8L, false, false),
        ANONYMOUS(null, false, false);

        final Long userId;
        /** memberships に在籍行があるか。 */
        final boolean activeMembership;
        /** 在籍の role_kind が MEMBER か（ADMIN / DEPUTY_ADMIN は MEMBER 種別で在籍する）。 */
        final boolean regularMember;

        Viewer(Long userId, boolean activeMembership, boolean regularMember) {
            this.userId = userId;
            this.activeMembership = activeMembership;
            this.regularMember = regularMember;
        }
    }

    enum TeamLevel { PUBLIC, GUESTS_AND_ABOVE, SUPPORTERS_AND_ABOVE, MEMBERS_AND_ABOVE }

    private static final Set<Viewer> CAN_READ_MEMBERS_ONLY =
            EnumSet.of(Viewer.ADMIN, Viewer.DEPUTY_ADMIN, Viewer.MEMBER);

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private MembersOnlyTeamQuery membersOnlyTeamQuery;

    private DefaultAccessControlService service;
    private ScopeContentAccessGate gate;

    @BeforeEach
    void setUp() {
        service = new DefaultAccessControlService(userRoleRepository, roleRepository, null, membershipRepository,
                null, null, new RoleScopeRoleQuery(userRoleRepository),
                List.of(new FamilyCareLinkQuery(null)), List.of(new GdprLastAdminWithdrawalErrorProvider()));
        gate = new ScopeContentAccessGate(service, membersOnlyTeamQuery);
        for (Viewer v : Viewer.values()) {
            if (v.userId == null) {
                continue;
            }
            lenient().when(membershipRepository.existsActiveByUserAndScope(v.userId, ScopeType.TEAM, TEAM_ID))
                    .thenReturn(v.activeMembership);
            lenient().when(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    v.userId, ScopeType.TEAM, TEAM_ID, RoleKind.MEMBER)).thenReturn(v.regularMember);
            lenient().when(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    v.userId, ScopeType.TEAM, TEAM_ID, RoleKind.SUPPORTER))
                    .thenReturn(v.activeMembership && !v.regularMember);
            lenient().when(membershipRepository.existsActiveByUserAndScope(v.userId, ScopeType.ORGANIZATION, ORG_ID))
                    .thenReturn(v.activeMembership);
        }
        lenient().when(userRoleRepository.existsSystemAdminByUserId(Viewer.SYSTEM_ADMIN.userId)).thenReturn(1L);
    }

    private void teamIs(TeamLevel level) {
        lenient().when(membersOnlyTeamQuery.findMembersOnlyTeamIds(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return level == TeamLevel.MEMBERS_AND_ABOVE && ids.contains(TEAM_ID) ? Set.of(TEAM_ID) : Set.of();
        });
    }

    static Stream<Arguments> allCombinations() {
        List<Arguments> args = new ArrayList<>();
        for (TeamLevel level : TeamLevel.values()) {
            for (Viewer viewer : Viewer.values()) {
                args.add(Arguments.of(level, viewer));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "AC-0159-04 {0} × {1}")
    @MethodSource("allCombinations")
    @DisplayName("V-0159-04-1 / V-0159-04-2 (AC-0159-04/07/09): 非公開チームは MEMBER 以上だけ、公開側は checkMembership と同一")
    void 門の真偽表(TeamLevel level, Viewer viewer) {
        teamIs(level);
        boolean expected = level == TeamLevel.MEMBERS_AND_ABOVE
                ? CAN_READ_MEMBERS_ONLY.contains(viewer)
                : viewer.activeMembership;

        assertThat(gate.canAccessScopeContent(viewer.userId, TEAM_ID, "TEAM"))
                .as("canAccessScopeContent(%s, %s)", level, viewer).isEqualTo(expected);
        if (expected) {
            assertThatCode(() -> gate.checkScopeContentAccess(viewer.userId, TEAM_ID, "TEAM"))
                    .doesNotThrowAnyException();
        } else {
            // 上限による拒否は checkMembership の部外者拒否と status・code まで完全一致（新コードを作らない）
            assertThatThrownBy(() -> gate.checkScopeContentAccess(viewer.userId, TEAM_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(t -> ((BusinessException) t).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    @ParameterizedTest(name = "AC-0159-05 {0} × {1}")
    @MethodSource("allCombinations")
    @DisplayName("AC-0159-05: checkMembership / checkMembershipForWrite の結果は在籍の有無だけで決まり、公開範囲で変わらない")
    void checkMembershipは本戦役前と同一(TeamLevel level, Viewer viewer) {
        teamIs(level);
        if (viewer.activeMembership) {
            assertThatCode(() -> service.checkMembership(viewer.userId, TEAM_ID, "TEAM")).doesNotThrowAnyException();
            assertThatCode(() -> gate.checkMembershipForWrite(viewer.userId, TEAM_ID, "TEAM"))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> service.checkMembership(viewer.userId, TEAM_ID, "TEAM"))
                    .extracting(t -> ((BusinessException) t).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
            assertThatThrownBy(() -> gate.checkMembershipForWrite(viewer.userId, TEAM_ID, "TEAM"))
                    .extracting(t -> ((BusinessException) t).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
        }
        assertThat(service.isMember(viewer.userId, TEAM_ID, "TEAM")).isEqualTo(viewer.activeMembership);
    }

    @ParameterizedTest(name = "AC-0159-94 前提 {0} × {1}")
    @MethodSource("allCombinations")
    @DisplayName("isBeyondTeamContentCap: 非公開チームで MEMBER 未満だけ true（在籍の有無は問わない）")
    void 上限越えの判定(TeamLevel level, Viewer viewer) {
        teamIs(level);
        boolean expected = level == TeamLevel.MEMBERS_AND_ABOVE && !CAN_READ_MEMBERS_ONLY.contains(viewer);
        assertThat(gate.isBeyondTeamContentCap(viewer.userId, TEAM_ID, "TEAM")).isEqualTo(expected);
    }

    @Nested
    @DisplayName("ORGANIZATION スコープ（AC-0159-04 後段）")
    class Organization {

        @Test
        @DisplayName("V-0159-04-3: ORGANIZATION は checkMembership と全件一致し、teams の問い合わせを撃たない")
        void 組織はcheckMembershipと同一() {
            for (Viewer viewer : Viewer.values()) {
                assertThat(gate.canAccessScopeContent(viewer.userId, ORG_ID, "ORGANIZATION"))
                        .as("%s", viewer).isEqualTo(viewer.activeMembership);
                assertThat(gate.isBeyondTeamContentCap(viewer.userId, ORG_ID, "ORGANIZATION")).isFalse();
            }
            verify(membersOnlyTeamQuery, never()).findMembersOnlyTeamIds(anyCollection());
        }
    }

    @Nested
    @DisplayName("filterAccessibleTeamIds（一括版）")
    class Bulk {

        private static final Long T_MO_SUPPORTER = 11L;
        private static final Long T_PUBLIC_SUPPORTER = 12L;
        private static final Long T_MO_MEMBER = 13L;
        private static final Long T_NOT_MEMBER = 14L;
        private static final Long USER = 99L;

        private MembershipRepository.TeamMembershipStateRow row(Long teamId, RoleKind kind) {
            return new MembershipRepository.TeamMembershipStateRow() {
                @Override public Long getTeamId() { return teamId; }
                @Override public Long getUserId() { return USER; }
                @Override public RoleKind getRoleKind() { return kind; }
                @Override public Boolean getActive() { return true; }
            };
        }

        @Test
        @DisplayName("非公開チームのサポーター在籍は落とし、公開チームのサポーター・非公開チームのメンバーは残す（問い合わせはチーム数に比例しない）")
        void 一括判定() {
            List<Long> teamIds = List.of(T_MO_SUPPORTER, T_PUBLIC_SUPPORTER, T_MO_MEMBER, T_NOT_MEMBER);
            lenient().when(membershipRepository.findTeamMembershipStateRows(anyCollection(), anyCollection()))
                    .thenReturn(List.of(row(T_MO_SUPPORTER, RoleKind.SUPPORTER),
                            row(T_PUBLIC_SUPPORTER, RoleKind.SUPPORTER), row(T_MO_MEMBER, RoleKind.MEMBER)));
            lenient().when(userRoleRepository.findExplicitTeamAdminKeys(anyCollection(), anyCollection()))
                    .thenReturn(List.of());
            lenient().when(membersOnlyTeamQuery.findMembersOnlyTeamIds(anyCollection())).thenAnswer(inv -> {
                Collection<?> ids = inv.getArgument(0);
                Set<Long> mo = new java.util.HashSet<>();
                for (Long id : List.of(T_MO_SUPPORTER, T_MO_MEMBER)) {
                    if (ids.contains(id)) {
                        mo.add(id);
                    }
                }
                return mo;
            });

            assertThat(gate.filterAccessibleTeamIds(USER, teamIds))
                    .containsExactlyInAnyOrder(T_PUBLIC_SUPPORTER, T_MO_MEMBER);
            // 在籍・管理者の一括取得 2 本＋非公開チーム判定 1 本。チームごとの問い合わせをしない
            verify(membershipRepository, times(1)).findTeamMembershipStateRows(anyCollection(), anyCollection());
            verify(membershipRepository, never()).existsActiveByUserAndScope(anyLong(), any(), anyLong());
            verify(membersOnlyTeamQuery, times(1)).findMembersOnlyTeamIds(anyCollection());
        }

        @Test
        @DisplayName("未ログイン・空入力は空集合で、問い合わせを撃たない")
        void 空() {
            assertThat(gate.filterAccessibleTeamIds(null, List.of(T_MO_MEMBER))).isEmpty();
            assertThat(gate.filterAccessibleTeamIds(USER, List.of())).isEmpty();
            verify(membershipRepository, never()).findTeamMembershipStateRows(anyCollection(), anyCollection());
        }
    }
}
