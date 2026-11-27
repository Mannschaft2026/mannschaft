package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolverTest.FakeProjection;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolverTest.FakeResolver;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolverTest.FakeVisibility;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

/**
 * 戦役 Z-A PR4（試練）: {@link AbstractContentVisibilityResolver} の SYSTEM_ADMIN 短絡撤去と ARCHIVED の閲覧者変更（AC6）。
 *
 * <ul>
 *   <li>ARCHIVED: 著者本人（スコープに今も所属）と、そのスコープの DEPUTY_ADMIN 以上だけが閲覧できる。
 *       所属を離れた著者・他テナントの ADMIN・MEMBER・運営アカウントは不可で、拒否理由は MEMBER と同じ NOT_OWNER
 *       （呼び出し側で不在と同じ 404 に写る）。PERSONAL スコープは著者本人のみ。</li>
 *   <li>DRAFT / SCHEDULED: 著者本人のみ（運営アカウントも不可）。</li>
 *   <li>D-13 高速パスの撤去: 運営アカウントは PUBLISHED でも実ロールで判定され、非所属と同じ結果・同じ拒否理由。</li>
 *   <li>委任ロール: withDelegatedRole は systemAdmin でも委任役割で差し替える。委任 ADMIN は ARCHIVED を同じ判定で通る。</li>
 * </ul>
 *
 * <p>マスター決定（2026-11-26）「ARCHIVED は著者本人とそのスコープの管理者が閲覧可。それ以外は不在と同じ」、
 * 軍議検分 Z-A (5)「管理者は DEPUTY_ADMIN 以上・所属を離れた著者・他テナント ADMIN・PERSONAL の扱いを AC に」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: 可視性 Resolver の SYSTEM_ADMIN 短絡撤去と ARCHIVED（AC6）")
class ContentVisibilitySysAdminBypassRemovalTest {

    private static final Long CONTENT_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long OTHER_TEAM_ID = 200L;
    private static final Long AUTHOR = 10L;
    private static final Long OTHER_USER = 20L;
    private static final Long SYSADMIN = 900L;
    private static final ScopeKey TEAM = new ScopeKey("TEAM", TEAM_ID);
    private static final ScopeKey OTHER_TEAM = new ScopeKey("TEAM", OTHER_TEAM_ID);

    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @Mock private VisibilityTemplateEvaluator templateEvaluator;
    @Mock private FollowBatchService followBatchService;
    @Mock private AuditLogService auditLogService;

    private FakeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FakeResolver(membershipBatchQueryService, templateEvaluator,
                new VisibilityMetrics(new SimpleMeterRegistry()), followBatchService, auditLogService);
    }

    private void content(String scopeType, Long scopeId, FakeVisibility visibility, ContentStatus status) {
        FakeProjection p = new FakeProjection();
        p.id = CONTENT_ID;
        p.scopeType = scopeType;
        p.scopeId = scopeId;
        p.authorUserId = AUTHOR;
        p.visibility = visibility;
        resolver.projections = List.of(p);
        resolver.statusOverride = status;
    }

    private void snapshot(UserScopeRoleSnapshot s) {
        when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet())).thenReturn(s);
        when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet(), anySet())).thenReturn(s);
    }

    private static UserScopeRoleSnapshot roles(Map<ScopeKey, String> roleByScope) {
        return new UserScopeRoleSnapshot(false, roleByScope, Map.of(), Set.of(), Set.of());
    }

    /** 所属の無い運営アカウント（PR4 後の MembershipBatchQueryService が返す形）。 */
    private static UserScopeRoleSnapshot platformAccount() {
        return new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of());
    }

    private boolean visible(Long viewer) {
        boolean filtered = resolver.filterAccessible(List.of(CONTENT_ID), viewer).contains(CONTENT_ID);
        boolean decided = resolver.decide(CONTENT_ID, viewer).allowed();
        assertThat(decided).as("filterAccessible と decide の判定は一致する").isEqualTo(filtered);
        return filtered;
    }

    // ========================================================================
    // ARCHIVED
    // ========================================================================

    @Nested
    @DisplayName("AC6 ARCHIVED（TEAM スコープ）: 著者本人と DEPUTY_ADMIN 以上だけ")
    class ArchivedTeamScope {

        @BeforeEach
        void archived() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("著者本人（スコープに MEMBER として所属）は閲覧できる")
        void author_member_allowed() {
            snapshot(roles(Map.of(TEAM, "MEMBER")));
            assertThat(visible(AUTHOR)).isTrue();
        }

        @Test
        @DisplayName("スコープの DEPUTY_ADMIN は閲覧できる")
        void deputyAdmin_allowed() {
            snapshot(roles(Map.of(TEAM, "DEPUTY_ADMIN")));
            assertThat(visible(OTHER_USER)).isTrue();
        }

        @Test
        @DisplayName("スコープの ADMIN は閲覧できる")
        void admin_allowed() {
            snapshot(roles(Map.of(TEAM, "ADMIN")));
            assertThat(visible(OTHER_USER)).isTrue();
        }

        @Test
        @DisplayName("ARCHIVED は visibility が ADMINS_AND_ABOVE でも DEPUTY_ADMIN は通らず、ADMIN は通る（visibility 軸は通常どおり）")
        void archived_visibilityAxisStillApplies() {
            content("TEAM", TEAM_ID, FakeVisibility.ADMINS_AND_ABOVE, ContentStatus.ARCHIVED);
            snapshot(roles(Map.of(TEAM, "DEPUTY_ADMIN")));
            assertThat(visible(OTHER_USER)).isFalse();
            snapshot(roles(Map.of(TEAM, "ADMIN")));
            assertThat(visible(OTHER_USER)).isTrue();
        }

        @Test
        @DisplayName("MEMBER（著者以外）は閲覧できず NOT_OWNER")
        void member_denied() {
            snapshot(roles(Map.of(TEAM, "MEMBER")));
            assertThat(visible(OTHER_USER)).isFalse();
            assertThat(resolver.decide(CONTENT_ID, OTHER_USER).denyReason()).isEqualTo(DenyReason.NOT_OWNER);
        }

        @Test
        @DisplayName("SUPPORTER（著者以外）は閲覧できない")
        void supporter_denied() {
            snapshot(roles(Map.of(TEAM, "SUPPORTER")));
            assertThat(visible(OTHER_USER)).isFalse();
        }

        @Test
        @DisplayName("所属を離れた著者は閲覧できず、MEMBER と同じ NOT_OWNER")
        void departedAuthor_denied() {
            snapshot(roles(Map.of()));
            assertThat(visible(AUTHOR)).isFalse();
            assertThat(resolver.decide(CONTENT_ID, AUTHOR).denyReason()).isEqualTo(DenyReason.NOT_OWNER);
        }

        @Test
        @DisplayName("他テナントの ADMIN は閲覧できず NOT_OWNER")
        void otherTenantAdmin_denied() {
            snapshot(roles(Map.of(OTHER_TEAM, "ADMIN")));
            assertThat(visible(OTHER_USER)).isFalse();
            assertThat(resolver.decide(CONTENT_ID, OTHER_USER).denyReason()).isEqualTo(DenyReason.NOT_OWNER);
        }

        @Test
        @DisplayName("運営アカウント（所属なし）は閲覧できず、MEMBER と同じ NOT_OWNER")
        void platformAccount_denied() {
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
            assertThat(resolver.decide(CONTENT_ID, SYSADMIN).denyReason()).isEqualTo(DenyReason.NOT_OWNER);
        }

        @Test
        @DisplayName("未認証は閲覧できない")
        void anonymous_denied() {
            snapshot(UserScopeRoleSnapshot.empty());
            assertThat(visible(null)).isFalse();
        }

        @Test
        @DisplayName("委任 ADMIN は withDelegatedRole を経て同じ判定で閲覧できる")
        void delegatedAdmin_allowed() {
            snapshot(roles(Map.of()));
            assertThatCode(() -> resolver.assertCanViewDelegated(CONTENT_ID, OTHER_USER, "ADMIN"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("委任 MEMBER は閲覧できない（VISIBILITY_001）")
        void delegatedMember_denied() {
            snapshot(roles(Map.of()));
            assertThatThrownBy(() -> resolver.assertCanViewDelegated(CONTENT_ID, OTHER_USER, "MEMBER"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("AC6 ARCHIVED（ORGANIZATION スコープ）: 組織の DEPUTY_ADMIN 以上")
    class ArchivedOrganizationScope {

        private final ScopeKey org = new ScopeKey("ORGANIZATION", 300L);

        @BeforeEach
        void archived() {
            content("ORGANIZATION", 300L, FakeVisibility.PUBLIC, ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("組織の DEPUTY_ADMIN は閲覧できる")
        void orgDeputyAdmin_allowed() {
            snapshot(roles(Map.of(org, "DEPUTY_ADMIN")));
            assertThat(visible(OTHER_USER)).isTrue();
        }

        @Test
        @DisplayName("組織の MEMBER（著者以外）は閲覧できない")
        void orgMember_denied() {
            snapshot(roles(Map.of(org, "MEMBER")));
            assertThat(visible(OTHER_USER)).isFalse();
        }

        @Test
        @DisplayName("配下チームの ADMIN は組織の ARCHIVED を閲覧できない（スコープの管理者ではない）")
        void childTeamAdmin_denied() {
            snapshot(new UserScopeRoleSnapshot(false, Map.of(TEAM, "ADMIN"), Map.of(TEAM, Set.of(300L)),
                    Set.of(), Set.of()));
            assertThat(visible(OTHER_USER)).isFalse();
        }
    }

    @Nested
    @DisplayName("AC6 ARCHIVED（PERSONAL スコープ）: 著者本人のみ")
    class ArchivedPersonalScope {

        @BeforeEach
        void archived() {
            content("PERSONAL", AUTHOR, FakeVisibility.PUBLIC, ContentStatus.ARCHIVED);
        }

        @Test
        @DisplayName("著者本人は閲覧できる")
        void author_allowed() {
            snapshot(roles(Map.of()));
            assertThat(visible(AUTHOR)).isTrue();
        }

        @Test
        @DisplayName("他の利用者は閲覧できない")
        void otherUser_denied() {
            snapshot(roles(Map.of()));
            assertThat(visible(OTHER_USER)).isFalse();
        }

        @Test
        @DisplayName("運営アカウントは閲覧できない")
        void platformAccount_denied() {
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
        }
    }

    // ========================================================================
    // DRAFT / SCHEDULED
    // ========================================================================

    @Nested
    @DisplayName("AC6 DRAFT / SCHEDULED: 著者本人のみ")
    class DraftScheduled {

        @Test
        @DisplayName("DRAFT: 著者本人は閲覧できる（ADMINS_AND_ABOVE でも visibility 軸をスキップ）")
        void draft_author_allowed() {
            content("TEAM", TEAM_ID, FakeVisibility.ADMINS_AND_ABOVE, ContentStatus.DRAFT);
            snapshot(roles(Map.of(TEAM, "MEMBER")));
            assertThat(visible(AUTHOR)).isTrue();
        }

        @Test
        @DisplayName("DRAFT: 運営アカウントは閲覧できず NOT_OWNER")
        void draft_platformAccount_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.DRAFT);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
            assertThat(resolver.decide(CONTENT_ID, SYSADMIN).denyReason()).isEqualTo(DenyReason.NOT_OWNER);
        }

        @Test
        @DisplayName("SCHEDULED: 運営アカウントは閲覧できない")
        void scheduled_platformAccount_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.SCHEDULED);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
        }

        @Test
        @DisplayName("DRAFT: スコープの ADMIN（著者以外）は閲覧できない（回帰）")
        void draft_scopeAdmin_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.DRAFT);
            snapshot(roles(Map.of(TEAM, "ADMIN")));
            assertThat(visible(OTHER_USER)).isFalse();
        }

        @Test
        @DisplayName("SCHEDULED: 著者本人は閲覧でき、MEMBER は閲覧できない（回帰）")
        void scheduled_authorOnly() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.SCHEDULED);
            snapshot(roles(Map.of(TEAM, "MEMBER")));
            assertThat(visible(AUTHOR)).isTrue();
            assertThat(visible(OTHER_USER)).isFalse();
        }
    }

    // ========================================================================
    // D-13 高速パスの撤去（PUBLISHED）
    // ========================================================================

    @Nested
    @DisplayName("D-13 高速パスの撤去: 運営アカウントは PUBLISHED でも非所属と同じ")
    class FastPathRemoved {

        @Test
        @DisplayName("MEMBERS_AND_ABOVE は不可、拒否理由は非所属と同じ NOT_A_MEMBER")
        void membersOnly_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.MEMBERS_AND_ABOVE, ContentStatus.PUBLISHED);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
            DenyReason sysAdminReason = resolver.decide(CONTENT_ID, SYSADMIN).denyReason();
            snapshot(roles(Map.of()));
            assertThat(sysAdminReason).isEqualTo(resolver.decide(CONTENT_ID, OTHER_USER).denyReason());
        }

        @Test
        @DisplayName("SCOPE_AFFILIATED は不可")
        void scopeAffiliated_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.SCOPE_AFFILIATED, ContentStatus.PUBLISHED);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
        }

        @Test
        @DisplayName("ORGANIZATION_WIDE は不可")
        void organizationWide_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.ORGANIZATION_WIDE, ContentStatus.PUBLISHED);
            snapshot(new UserScopeRoleSnapshot(true, Map.of(), Map.of(TEAM, Set.of(300L)), Set.of(), Set.of()));
            assertThat(visible(SYSADMIN)).isFalse();
        }

        @Test
        @DisplayName("PRIVATE（他人の投稿）は不可")
        void private_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.PRIVATE, ContentStatus.PUBLISHED);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
        }

        @Test
        @DisplayName("親 ORG が非アクティブなら PUBLIC でも不可（§11.6 を素通りしない）")
        void parentOrgInactive_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.PUBLISHED);
            snapshot(new UserScopeRoleSnapshot(true, Map.of(), Map.of(TEAM, Set.of(300L)), Set.of(), Set.of(300L)));
            assertThat(visible(SYSADMIN)).isFalse();
        }

        @Test
        @DisplayName("PUBLIC は誰でも見えるので運営アカウントも閲覧できる（回帰）")
        void public_allowed() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.PUBLISHED);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isTrue();
        }

        @Test
        @DisplayName("DELETED は運営アカウントも不可で NOT_FOUND（回帰）")
        void deleted_notFound() {
            content("TEAM", TEAM_ID, FakeVisibility.PUBLIC, ContentStatus.DELETED);
            snapshot(platformAccount());
            assertThat(visible(SYSADMIN)).isFalse();
            assertThat(resolver.decide(CONTENT_ID, SYSADMIN).denyReason()).isEqualTo(DenyReason.NOT_FOUND);
        }

        @Test
        @DisplayName("所属のある SYSTEM_ADMIN（兼任）は実ロールで判定: MEMBER なら MEMBERS_AND_ABOVE 可・ADMINS_AND_ABOVE 不可")
        void sysAdminWithAffiliation_realRole() {
            snapshot(new UserScopeRoleSnapshot(true, Map.of(TEAM, "MEMBER"), Map.of(), Set.of(), Set.of()));
            content("TEAM", TEAM_ID, FakeVisibility.MEMBERS_AND_ABOVE, ContentStatus.PUBLISHED);
            assertThat(visible(SYSADMIN)).isTrue();
            content("TEAM", TEAM_ID, FakeVisibility.ADMINS_AND_ABOVE, ContentStatus.PUBLISHED);
            assertThat(visible(SYSADMIN)).isFalse();
        }
    }

    // ========================================================================
    // withDelegatedRole
    // ========================================================================

    @Nested
    @DisplayName("withDelegatedRole: systemAdmin でも委任役割で差し替える")
    class DelegatedRole {

        @Test
        @DisplayName("運営アカウント＋委任 SUPPORTER は MEMBERS_AND_ABOVE を満たさない")
        void platformAccountDelegatedSupporter_denied() {
            content("TEAM", TEAM_ID, FakeVisibility.MEMBERS_AND_ABOVE, ContentStatus.PUBLISHED);
            snapshot(platformAccount());
            assertThatThrownBy(() -> resolver.assertCanViewDelegated(CONTENT_ID, SYSADMIN, "SUPPORTER"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("運営アカウント＋委任 MEMBER は MEMBERS_AND_ABOVE を満たす（委任の意味は変えない）")
        void platformAccountDelegatedMember_allowed() {
            content("TEAM", TEAM_ID, FakeVisibility.MEMBERS_AND_ABOVE, ContentStatus.PUBLISHED);
            snapshot(platformAccount());
            assertThatCode(() -> resolver.assertCanViewDelegated(CONTENT_ID, SYSADMIN, "MEMBER"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("一般利用者＋委任 SUPPORTER は SUPPORTERS_AND_ABOVE を満たす（回帰）")
        void generalDelegatedSupporter_regression() {
            content("TEAM", TEAM_ID, FakeVisibility.SUPPORTERS_AND_ABOVE, ContentStatus.PUBLISHED);
            snapshot(roles(Map.of()));
            assertThatCode(() -> resolver.assertCanViewDelegated(CONTENT_ID, OTHER_USER, "SUPPORTER"))
                    .doesNotThrowAnyException();
        }
    }
}
