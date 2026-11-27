package com.mannschaft.app.cms.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 戦役 Z-A PR4（試練）: {@link BlogPostVisibilityResolver} の SYSTEM_ADMIN 短絡撤去（isScopeAdmin の課金ゲート素通し）。
 *
 * <p>運営アカウント（所属なし）は課金ゲートを素通りせず、一般の非所属利用者と同じく課金結果に従う。
 * 課金ゲートを素通しできるのは対象スコープの ADMIN だけ（回帰）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: BlogPostVisibilityResolver の SYSTEM_ADMIN 短絡撤去")
class BlogPostVisibilityResolverSysAdminBypassRemovalTest {

    @Mock private BlogPostRepository blogPostRepository;
    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @Mock private VisibilityTemplateEvaluator templateEvaluator;
    @Mock private FollowBatchService followBatchService;
    @Mock private AuditLogService auditLogService;
    @Mock private PaymentGateService paymentGateService;

    private BlogPostVisibilityResolver resolver;

    private static final Long TEAM_ID = 100L;
    private static final Long AUTHOR_ID = 1L;
    private static final Long SYSADMIN = 900L;
    private static final Long POST_ID = 99L;
    private static final ScopeKey TEAM = new ScopeKey("TEAM", TEAM_ID);

    @BeforeEach
    void setUp() {
        resolver = new BlogPostVisibilityResolver(blogPostRepository, paymentGateService,
                membershipBatchQueryService, templateEvaluator,
                new VisibilityMetrics(new SimpleMeterRegistry()), followBatchService, auditLogService);
    }

    private void post(Visibility visibility, PostStatus status) {
        when(blogPostRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(
                BlogPostVisibilityProjection.of(POST_ID, TEAM_ID, null, AUTHOR_ID, null, visibility, status)));
    }

    private void snapshot(UserScopeRoleSnapshot s) {
        when(membershipBatchQueryService.snapshotForUser(any(), any(), any())).thenReturn(s);
    }

    private void unpaidHiddenGate() {
        when(paymentGateService.checkAccessBatch(eq("POST"), any(), any(), any(Map.class)))
                .thenReturn(Map.of(POST_ID, new GateCheckResponse(false, true, List.of())));
        when(paymentGateService.checkAccess(eq("POST"), eq(POST_ID), any(), any(ContentGateTarget.class)))
                .thenReturn(new GateCheckResponse(false, true, List.of()));
    }

    private static UserScopeRoleSnapshot platformAccount() {
        return new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of());
    }

    @Test
    @DisplayName("PUBLIC・未課金の titleHidden ゲート: 運営アカウントは素通りせず不可視")
    void publicHiddenGate_platformAccount_denied() {
        post(Visibility.PUBLIC, PostStatus.PUBLISHED);
        snapshot(platformAccount());
        unpaidHiddenGate();
        assertThat(resolver.canView(POST_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("CUSTOM（ペイウォール）・未課金: 運営アカウントは閲覧できない")
    void customUnpaid_platformAccount_denied() {
        post(Visibility.CUSTOM, PostStatus.PUBLISHED);
        snapshot(platformAccount());
        unpaidHiddenGate();
        assertThat(resolver.canView(POST_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("MEMBERS_ONLY: 運営アカウントは閲覧できない")
    void membersOnly_platformAccount_denied() {
        post(Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
        snapshot(platformAccount());
        when(paymentGateService.checkAccessBatch(eq("POST"), any(), any(), any(Map.class)))
                .thenReturn(Map.of(POST_ID, new GateCheckResponse(true, false, List.of())));
        assertThat(resolver.canView(POST_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("ARCHIVED: 運営アカウントは閲覧できない")
    void archived_platformAccount_denied() {
        post(Visibility.PUBLIC, PostStatus.ARCHIVED);
        snapshot(platformAccount());
        when(paymentGateService.checkAccessBatch(eq("POST"), any(), any(), any(Map.class)))
                .thenReturn(Map.of(POST_ID, new GateCheckResponse(true, false, List.of())));
        assertThat(resolver.canView(POST_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("回帰: 対象スコープの ADMIN は課金ゲートを素通しする")
    void scopeAdmin_bypassesGate_regression() {
        post(Visibility.PUBLIC, PostStatus.PUBLISHED);
        snapshot(new UserScopeRoleSnapshot(false, Map.of(TEAM, "ADMIN"), Map.of(), Set.of(), Set.of()));
        unpaidHiddenGate();
        assertThat(resolver.canView(POST_ID, 2L)).isTrue();
    }

    @Test
    @DisplayName("回帰: 非所属の一般利用者は未課金の titleHidden ゲートで不可視")
    void nonMember_hiddenGate_regression() {
        post(Visibility.PUBLIC, PostStatus.PUBLISHED);
        snapshot(UserScopeRoleSnapshot.empty());
        unpaidHiddenGate();
        assertThat(resolver.canView(POST_ID, 2L)).isFalse();
    }
}
