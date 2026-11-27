package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-261124-0159 PR-01 試練: Resolver の親チーム上限（F00 §20.2・AC-0159-01 / 02 / 07 / 09）。
 *
 * <p>{@link AbstractContentVisibilityResolver} の共通テンプレートが、所属チームが非公開チーム
 * （{@link UserScopeRoleSnapshot#membersOnlyTeamIds()}）のとき、コンテンツ自身の段の判定に加えて
 * 「閲覧者がそのチームを閲覧できること（MEMBER 以上）」を AND で課すことを、
 * チーム 4 段 × 閲覧者 8 区分＋未ログインの全組合せで固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261124-0159 Resolver の親チーム上限（AC-0159-01/02/07/09）")
class MembersOnlyTeamCapResolverTest {

    private static final long TEAM_ID = 700L;
    private static final long ORG_ID = 800L;
    private static final long AUTHOR = 9_000L;
    private static final ScopeKey TEAM_SCOPE = new ScopeKey("TEAM", TEAM_ID);
    private static final ScopeKey ORG_SCOPE = new ScopeKey("ORGANIZATION", ORG_ID);

    enum Viewer { ADMIN, DEPUTY_ADMIN, MEMBER, SUPPORTER, GUEST, PARENT_ORG_ADMIN, OUTSIDER, SYSTEM_ADMIN, ANONYMOUS }

    enum TeamLevel { PUBLIC, GUESTS_AND_ABOVE, SUPPORTERS_AND_ABOVE, MEMBERS_AND_ABOVE }

    private static final Set<Viewer> MEMBER_OR_ABOVE =
            EnumSet.of(Viewer.ADMIN, Viewer.DEPUTY_ADMIN, Viewer.MEMBER, Viewer.SYSTEM_ADMIN);

    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @Mock private VisibilityTemplateEvaluator templateEvaluator;
    @Mock private FollowBatchService followBatchService;
    @Mock private AuditLogService auditLogService;

    private CapResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CapResolver(membershipBatchQueryService, templateEvaluator,
                new VisibilityMetrics(new SimpleMeterRegistry()), followBatchService, auditLogService);
    }

    private static Long userIdOf(Viewer viewer) {
        return viewer == Viewer.ANONYMOUS ? null : 100L + viewer.ordinal();
    }

    /** 閲覧者区分から、MembershipBatchQueryService が組み立てるのと同じ形のスナップショットを作る。 */
    private static UserScopeRoleSnapshot snapshotOf(Viewer viewer, TeamLevel level) {
        Set<Long> membersOnly = level == TeamLevel.MEMBERS_AND_ABOVE ? Set.of(TEAM_ID) : Set.of();
        if (viewer == Viewer.SYSTEM_ADMIN) {
            return UserScopeRoleSnapshot.forSystemAdmin();
        }
        Map<ScopeKey, String> roles = new HashMap<>();
        Map<ScopeKey, String> orgRoles = new HashMap<>();
        Set<ScopeKey> orgMemberOf = Set.of();
        switch (viewer) {
            case ADMIN -> roles.put(TEAM_SCOPE, "ADMIN");
            case DEPUTY_ADMIN -> roles.put(TEAM_SCOPE, "DEPUTY_ADMIN");
            case MEMBER -> roles.put(TEAM_SCOPE, "MEMBER");
            case SUPPORTER -> roles.put(TEAM_SCOPE, "SUPPORTER");
            case GUEST -> roles.put(TEAM_SCOPE, "GUEST");
            case PARENT_ORG_ADMIN -> {
                orgRoles.put(ORG_SCOPE, "ADMIN");
                orgMemberOf = Set.of(ORG_SCOPE);
            }
            default -> { }
        }
        return new UserScopeRoleSnapshot(false, Map.copyOf(roles), Map.of(TEAM_SCOPE, Set.of(ORG_ID)),
                orgMemberOf, Set.of(), Set.of(), Map.copyOf(orgRoles), Map.of(), membersOnly);
    }

    private void viewerIs(Viewer viewer, TeamLevel level) {
        when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                .thenReturn(snapshotOf(viewer, level));
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

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("allCombinations")
    @DisplayName("AC-0159-01/02/07/09: PUBLIC のコンテンツは、非公開チームでは MEMBER 以上と SystemAdmin だけ、公開側は全員に見える")
    void 自身がPUBLICのコンテンツ(TeamLevel level, Viewer viewer) {
        resolver.projections = List.of(new Row(1L, TEAM_ID, AUTHOR, CapVisibility.PUBLIC));
        viewerIs(viewer, level);
        boolean expected = level != TeamLevel.MEMBERS_AND_ABOVE || MEMBER_OR_ABOVE.contains(viewer);

        assertThat(resolver.canView(1L, userIdOf(viewer))).as("canView").isEqualTo(expected);
        assertThat(resolver.filterAccessible(List.of(1L), userIdOf(viewer)).contains(1L))
                .as("filterAccessible").isEqualTo(expected);
        VisibilityDecision decision = resolver.decide(1L, userIdOf(viewer));
        assertThat(decision.allowed()).as("decide").isEqualTo(expected);
        if (!expected) {
            // 実在するコンテンツの上限拒否は NOT_FOUND に倒さない（assertCanView は既存どおり VISIBILITY_001）
            assertThat(decision.denyReason()).isNotEqualTo(DenyReason.NOT_FOUND);
        }
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("allCombinations")
    @DisplayName("AC-0159-02: SUPPORTERS_AND_ABOVE のコンテンツは、公開側では本戦役前の段判定どおり、非公開チームでは MEMBER 以上だけ")
    void 自身がSUPPORTERS以上のコンテンツ(TeamLevel level, Viewer viewer) {
        resolver.projections = List.of(new Row(2L, TEAM_ID, AUTHOR, CapVisibility.SUPPORTERS_AND_ABOVE));
        viewerIs(viewer, level);
        boolean byLevel = MEMBER_OR_ABOVE.contains(viewer) || viewer == Viewer.SUPPORTER;
        boolean expected = level == TeamLevel.MEMBERS_AND_ABOVE ? MEMBER_OR_ABOVE.contains(viewer) : byLevel;

        assertThat(resolver.canView(2L, userIdOf(viewer))).isEqualTo(expected);
    }

    @Test
    @DisplayName("非公開チームの下書きは、作成者本人でもチームでのロールが MEMBER 未満なら見えない（上限は作者特例より先）")
    void 下書きの作者特例も上限を越えない() {
        long supporterId = userIdOf(Viewer.SUPPORTER);
        resolver.projections = List.of(new Row(3L, TEAM_ID, supporterId, CapVisibility.PUBLIC));
        resolver.status = ContentStatus.DRAFT;
        viewerIs(Viewer.SUPPORTER, TeamLevel.MEMBERS_AND_ABOVE);
        assertThat(resolver.canView(3L, supporterId)).isFalse();
        assertThat(resolver.decide(3L, supporterId).allowed()).isFalse();

        viewerIs(Viewer.SUPPORTER, TeamLevel.SUPPORTERS_AND_ABOVE);
        assertThat(resolver.canView(3L, supporterId)).as("公開側では従来どおり作者は見える").isTrue();
    }

    @Test
    @DisplayName("ORGANIZATION スコープのコンテンツには上限を課さない（非公開チーム集合に同じ ID があっても）")
    void 組織スコープには効かない() {
        resolver.projections = List.of(new Row(4L, ORG_ID, AUTHOR, CapVisibility.PUBLIC, "ORGANIZATION"));
        when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty().withMembersOnlyTeamIds(Set.of(ORG_ID)));
        assertThat(resolver.canView(4L, null)).isTrue();
    }

    @Test
    @DisplayName("AC-0159-03（単体）: 50 チーム混在の 500 件でもスナップショット構築は 1 回で、非公開チーム分だけ落ちる")
    void 一括判定はスナップショット1回() {
        List<Row> rows = new ArrayList<>();
        Set<Long> membersOnly = new java.util.HashSet<>();
        for (long i = 0; i < 500; i++) {
            long team = 10_000L + (i % 50);
            rows.add(new Row(i + 1, team, AUTHOR, CapVisibility.PUBLIC));
            if (team % 4 == 0) {
                membersOnly.add(team);
            }
        }
        resolver.projections = rows;
        when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                .thenReturn(UserScopeRoleSnapshot.empty().withMembersOnlyTeamIds(membersOnly));

        Set<Long> visible = resolver.filterAccessible(rows.stream().map(Row::id).toList(), 55L);

        long expected = rows.stream().filter(r -> !membersOnly.contains(r.scopeId())).count();
        assertThat(visible).hasSize((int) expected);
        verify(membershipBatchQueryService, times(1)).snapshotForUser(any(), anySet(), anySet());
    }

    // ───────── テスト用の最小 Resolver ─────────

    enum CapVisibility { PUBLIC, SUPPORTERS_AND_ABOVE }

    record Row(Long id, Long scopeId, Long authorUserId, CapVisibility vis, String scopeType)
            implements VisibilityProjection {
        Row(Long id, Long scopeId, Long authorUserId, CapVisibility vis) {
            this(id, scopeId, authorUserId, vis, "TEAM");
        }

        @Override public Long visibilityTemplateId() { return null; }
        @Override public Object visibility() { return vis; }
    }

    static class CapResolver extends AbstractContentVisibilityResolver<CapVisibility, Row> {
        List<Row> projections = List.of();
        ContentStatus status = ContentStatus.PUBLISHED;

        CapResolver(MembershipBatchQueryService m, VisibilityTemplateEvaluator t, VisibilityMetrics v,
                    FollowBatchService f, AuditLogService a) {
            super(m, t, v, f, a);
        }

        @Override public ReferenceType referenceType() { return ReferenceType.BLOG_POST; }

        @Override protected List<Row> loadProjections(Collection<Long> ids) {
            return projections.stream().filter(r -> ids.contains(r.id())).toList();
        }

        @Override protected StandardVisibility toStandard(CapVisibility visibility) {
            return visibility == CapVisibility.PUBLIC
                    ? StandardVisibility.PUBLIC : StandardVisibility.SUPPORTERS_AND_ABOVE;
        }

        @Override protected ContentStatus toContentStatus(Row row) { return status; }
    }
}
