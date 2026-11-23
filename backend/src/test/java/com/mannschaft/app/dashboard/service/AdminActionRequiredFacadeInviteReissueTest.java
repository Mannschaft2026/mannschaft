package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.service.PendingInviteAdminQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.matching.service.MatchingAdminQueryService;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService;
import com.mannschaft.app.role.service.InviteIssuerPolicy;
import com.mannschaft.app.shift.service.ShiftRequestAdminQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261107-1833 PR3 N4: 管理者向け承認待ち集約の INVITE_REISSUE（再招待依頼）ドメイン（設計 b・i）。
 *
 * <p>INVITE_REISSUE は招待権限者（{@link InviteIssuerPolicy#canIssue} が真）にだけ含める。list_route は
 * {@code /{teams|organizations}/{slug}/invites#reissue-requests}。一時障害は他ドメインと同じく degraded に縮退し、
 * total に加算しない。preview_size はそのまま渡す（0 なら件数だけ）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminActionRequiredFacade INVITE_REISSUE（CMP-261107-1833 PR3 N4）")
class AdminActionRequiredFacadeInviteReissueTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "dev-team";
    private static final Long ORG_ID = 20L;
    private static final String ORG_SLUG = "dev-org";

    @Mock private AccessControlService accessControlService;
    @Mock private ReservationAdminQueryService reservationAdminQueryService;
    @Mock private ShiftRequestAdminQueryService shiftRequestAdminQueryService;
    @Mock private MatchingAdminQueryService matchingAdminQueryService;
    @Mock private PaymentAdminQueryService paymentAdminQueryService;
    @Mock private PendingInviteAdminQueryService pendingInviteAdminQueryService;
    @Mock private InviteIssuerPolicy inviteIssuerPolicy;

    @InjectMocks private AdminActionRequiredFacade facade;

    private static PendingAggregate agg(long count, int items, String route) {
        List<PendingAggregate.Item> list = new java.util.ArrayList<>();
        for (int i = 0; i < items; i++) {
            list.add(new PendingAggregate.Item("id-" + i, "依頼者" + i, "依頼者" + i, LocalDateTime.now(), route));
        }
        return new PendingAggregate(count, list);
    }

    private void teamDomainsEmpty() {
        given(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(0, 0, null));
        given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(0, 0, null));
        given(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(0, 0, null));
    }

    private static Optional<AdminActionRequiredResponse.DomainSection> section(AdminActionRequiredResponse res) {
        return res.domains().stream().filter(d -> "INVITE_REISSUE".equals(d.domain())).findFirst();
    }

    @Test
    @DisplayName("N4: 招待権限者（TEAM）には INVITE_REISSUE を含め、件数・list_route・プレビューを返し、total に加算する")
    void teamInviterSeesReissue() {
        teamDomainsEmpty();
        given(inviteIssuerPolicy.canIssue(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        String route = "/teams/" + TEAM_SLUG + "/invites#reissue-requests";
        given(pendingInviteAdminQueryService.reissueRequestsFor(eq("TEAM"), eq(TEAM_ID), eq(TEAM_SLUG), eq(3)))
                .willReturn(agg(2, 2, route));

        AdminActionRequiredResponse res = facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);

        AdminActionRequiredResponse.DomainSection s = section(res).orElseThrow(() -> new AssertionError("INVITE_REISSUE が無い"));
        assertThat(s.pendingCount()).isEqualTo(2);
        assertThat(s.degraded()).isFalse();
        assertThat(s.listRoute()).isEqualTo(route);
        assertThat(s.items()).hasSize(2).allSatisfy(i -> assertThat(i.detailRoute()).isEqualTo(route));
        assertThat(res.totalPending()).isEqualTo(2);
    }

    @Test
    @DisplayName("N4: 招待権限者（ORGANIZATION）には PAYMENT と INVITE_REISSUE を含める")
    void orgInviterSeesReissue() {
        given(paymentAdminQueryService.unsettledForOrg(eq(ORG_ID), eq(ORG_SLUG), anyInt())).willReturn(agg(1, 0, null));
        given(inviteIssuerPolicy.canIssue(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(pendingInviteAdminQueryService.reissueRequestsFor(eq("ORGANIZATION"), eq(ORG_ID), eq(ORG_SLUG), anyInt()))
                .willReturn(agg(3, 0, null));

        AdminActionRequiredResponse res = facade.getAdminActionRequired(USER_ID, "ORGANIZATION", ORG_ID, ORG_SLUG, 0);

        assertThat(res.domains()).extracting(AdminActionRequiredResponse.DomainSection::domain)
                .containsExactlyInAnyOrder("PAYMENT", "INVITE_REISSUE");
        assertThat(section(res).orElseThrow().listRoute()).isEqualTo("/organizations/" + ORG_SLUG + "/invites#reissue-requests");
        assertThat(res.totalPending()).isEqualTo(4);
    }

    @Test
    @DisplayName("N4: 招待権限の無い利用者（INVITE_MEMBERS なしの DEPUTY など）には INVITE_REISSUE を含めず、集計もしない")
    void nonInviterDoesNotSeeReissue() {
        teamDomainsEmpty();
        given(inviteIssuerPolicy.canIssue(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        AdminActionRequiredResponse res = facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);

        assertThat(section(res)).isEmpty();
        verify(inviteIssuerPolicy).canIssue(USER_ID, TEAM_ID, "TEAM");
        verify(pendingInviteAdminQueryService, never()).reissueRequestsFor(eq("TEAM"), eq(TEAM_ID), eq(TEAM_SLUG), anyInt());
    }

    @Test
    @DisplayName("N4: INVITE_REISSUE の一時障害は degraded に縮退し total に加算しない（他ドメインは返す）")
    void transientFailureDegrades() {
        given(reservationAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(1, 0, null));
        given(shiftRequestAdminQueryService.pendingForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(0, 0, null));
        given(matchingAdminQueryService.pendingReceivedForTeam(eq(TEAM_ID), eq(TEAM_SLUG), anyInt())).willReturn(agg(0, 0, null));
        given(inviteIssuerPolicy.canIssue(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(pendingInviteAdminQueryService.reissueRequestsFor(eq("TEAM"), eq(TEAM_ID), eq(TEAM_SLUG), anyInt()))
                .willThrow(new DataAccessResourceFailureException("試練: 一時障害"));

        AdminActionRequiredResponse res = facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 3);

        AdminActionRequiredResponse.DomainSection s = section(res).orElseThrow(() -> new AssertionError("INVITE_REISSUE が無い"));
        assertThat(s.degraded()).isTrue();
        assertThat(s.pendingCount()).isZero();
        assertThat(res.totalPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("N4/L5: preview_size=0 をそのまま渡す（件数だけの集計）")
    void previewSizeZeroIsPassedThrough() {
        teamDomainsEmpty();
        given(inviteIssuerPolicy.canIssue(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(pendingInviteAdminQueryService.reissueRequestsFor(eq("TEAM"), eq(TEAM_ID), eq(TEAM_SLUG), eq(0)))
                .willReturn(agg(5, 0, null));

        AdminActionRequiredResponse res = facade.getAdminActionRequired(USER_ID, "TEAM", TEAM_ID, TEAM_SLUG, 0);

        assertThat(section(res).orElseThrow(() -> new AssertionError("INVITE_REISSUE が無い")).pendingCount()).isEqualTo(5);
        verify(pendingInviteAdminQueryService).reissueRequestsFor("TEAM", TEAM_ID, TEAM_SLUG, 0);
    }
}
