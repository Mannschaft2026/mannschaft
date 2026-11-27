package com.mannschaft.app.social.announcement.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementSourcePublicationGuard;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 戦役 Z-A PR4（試練）: {@link AnnouncementFeedVisibilityResolver} の SYSTEM_ADMIN 短絡撤去（isScopeAdmin の課金ゲート素通し）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: AnnouncementFeedVisibilityResolver の SYSTEM_ADMIN 短絡撤去")
class AnnouncementFeedVisibilityResolverSysAdminBypassRemovalTest {

    @Mock private AnnouncementFeedRepository announcementFeedRepository;
    @Mock private PaymentGateService paymentGateService;
    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @Mock private VisibilityTemplateEvaluator templateEvaluator;
    @Mock private FollowBatchService followBatchService;
    @Mock private AuditLogService auditLogService;
    @Mock private AnnouncementSourcePublicationGuard sourcePublication;

    private static final Long TEAM_ID = 100L;
    private static final Long FEED_ID = 10L;
    private static final Long SYSADMIN = 900L;
    private static final ScopeKey TEAM = new ScopeKey("TEAM", TEAM_ID);

    @BeforeEach
    void setUp() {
        when(sourcePublication.retainPublished(any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<>((Collection<?>) inv.getArgument(0)));
    }

    private AnnouncementFeedVisibilityResolver resolverWith(AnnouncementFeedVisibility visibility) {
        AnnouncementFeedVisibilityProjection p = new AnnouncementFeedVisibilityProjection(
                FEED_ID, "TEAM", TEAM_ID, null, null, visibility, null, null);
        return new AnnouncementFeedVisibilityResolver(announcementFeedRepository, paymentGateService,
                sourcePublication, membershipBatchQueryService, templateEvaluator,
                new VisibilityMetrics(new SimpleMeterRegistry()), followBatchService, auditLogService) {
            @Override
            protected List<AnnouncementFeedVisibilityProjection> loadProjections(Collection<Long> ids) {
                return List.of(p);
            }
        };
    }

    private void snapshot(UserScopeRoleSnapshot s) {
        when(membershipBatchQueryService.snapshotForUser(any(), any(), any())).thenReturn(s);
    }

    private void unpaidHiddenGate() {
        when(paymentGateService.checkAccessBatch(eq(ContentGateType.ANNOUNCEMENT), any(), any(), any(Map.class)))
                .thenReturn(Map.of(FEED_ID, new GateCheckResponse(false, true, List.of())));
        when(paymentGateService.checkAccess(eq(ContentGateType.ANNOUNCEMENT), eq(FEED_ID), any(),
                any(ContentGateTarget.class)))
                .thenReturn(new GateCheckResponse(false, true, List.of()));
    }

    private static UserScopeRoleSnapshot platformAccount() {
        return new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of());
    }

    @Test
    @DisplayName("PUBLIC・未課金の titleHidden ゲート: 運営アカウントは素通りせず不可視")
    void publicHiddenGate_platformAccount_denied() {
        snapshot(platformAccount());
        unpaidHiddenGate();
        assertThat(resolverWith(AnnouncementFeedVisibility.PUBLIC).canView(FEED_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("CUSTOM（ペイウォール）・未課金: 運営アカウントは閲覧できない")
    void customUnpaid_platformAccount_denied() {
        snapshot(platformAccount());
        unpaidHiddenGate();
        assertThat(resolverWith(AnnouncementFeedVisibility.CUSTOM).canView(FEED_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("MEMBERS_AND_ABOVE: 運営アカウントは閲覧できない")
    void membersOnly_platformAccount_denied() {
        snapshot(platformAccount());
        when(paymentGateService.checkAccessBatch(eq(ContentGateType.ANNOUNCEMENT), any(), any(), any(Map.class)))
                .thenReturn(Map.of(FEED_ID, new GateCheckResponse(true, false, List.of())));
        assertThat(resolverWith(AnnouncementFeedVisibility.MEMBERS_AND_ABOVE).canView(FEED_ID, SYSADMIN))
                .isFalse();
    }

    @Test
    @DisplayName("回帰: 対象スコープの ADMIN は課金ゲートを素通しする")
    void scopeAdmin_bypassesGate_regression() {
        snapshot(new UserScopeRoleSnapshot(false, Map.of(TEAM, "ADMIN"), Map.of(), Set.of(), Set.of()));
        unpaidHiddenGate();
        assertThat(resolverWith(AnnouncementFeedVisibility.PUBLIC).canView(FEED_ID, 2L)).isTrue();
    }

    @Test
    @DisplayName("回帰: 非所属の一般利用者は未課金の titleHidden ゲートで不可視")
    void nonMember_hiddenGate_regression() {
        snapshot(UserScopeRoleSnapshot.empty());
        unpaidHiddenGate();
        assertThat(resolverWith(AnnouncementFeedVisibility.PUBLIC).canView(FEED_ID, 2L)).isFalse();
    }
}
