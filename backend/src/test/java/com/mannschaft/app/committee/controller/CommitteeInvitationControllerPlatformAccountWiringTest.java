package com.mannschaft.app.committee.controller;

import com.mannschaft.app.committee.dto.CommitteeInviteRequest;
import com.mannschaft.app.committee.dto.InviteByTokenRequest;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.service.CommitteeInvitationService;
import com.mannschaft.app.committee.service.CommitteeInvitationService.SendInvitationsResult;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.GENERAL_USER;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.OTHER_GENERAL_USER;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.PLATFORM_USER;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.assertForbiddenAsPlatformAccount;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.authenticateAs;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.clearAuthentication;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.realPolicy;
import static com.mannschaft.app.role.service.PlatformAccountWiringSupport.servicePassingThrough;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CommitteeInvitationController} の運営アカウント所属禁止の配線試験（戦役 Z-A PR2 再検分指摘 1a）。
 *
 * <p>本物の {@link PlatformAccountAffiliationPolicy}（運営ロールの存在判定だけモック）で、
 * 「一般利用者は通る」「運営アカウントは 403 MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN」を確かめる。
 * Service は本番の取引内の拒否と同じ判定を、本番 Service が判定する利用者 ID に対して行うスタブ。
 * 方式の詳細は {@code PlatformAccountWiringSupport}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CommitteeInvitationController 運営アカウント所属禁止の配線")
class CommitteeInvitationControllerPlatformAccountWiringTest {

    @Spy
    private PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy = realPolicy();
    @Mock
    private CommitteeInvitationService committeeInvitationService;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private CommitteeInvitationController controller;

    @AfterEach
    void tearDown() {
        clearAuthentication();
    }

    @Test
    @DisplayName("招集状送付: 招集先がすべて一般利用者なら通り、1 人でも運営アカウントなら 403（宛先を一括で判定）")
    void sendInvitationsJudgesAllInvitees() {
        authenticateAs(GENERAL_USER);
        SendInvitationsResult ok = new SendInvitationsResult(List.of(), 0, 0);

        CommitteeInviteRequest general = new CommitteeInviteRequest();
        general.setInviteeUserIds(List.of(OTHER_GENERAL_USER, 102L));
        doAnswer(servicePassingThrough(ok, OTHER_GENERAL_USER, 102L))
                .when(committeeInvitationService).sendInvitations(any(), any(), any());
        assertThat(controller.sendInvitations(1L, general).getStatusCode().value()).isEqualTo(201);

        CommitteeInviteRequest mixed = new CommitteeInviteRequest();
        mixed.setInviteeUserIds(List.of(OTHER_GENERAL_USER, PLATFORM_USER));
        doAnswer(servicePassingThrough(ok, OTHER_GENERAL_USER, PLATFORM_USER))
                .when(committeeInvitationService).sendInvitations(any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.sendInvitations(1L, mixed));
    }

    @Test
    @DisplayName("招集受諾: 一般利用者は通り、運営アカウントは 403")
    void acceptInvitation() {
        CommitteeMemberEntity member = mock(CommitteeMemberEntity.class);
        when(member.getUserId()).thenReturn(GENERAL_USER);
        InviteByTokenRequest request = new InviteByTokenRequest();
        request.setInviteToken("token");

        authenticateAs(GENERAL_USER);
        doAnswer(servicePassingThrough(member, GENERAL_USER))
                .when(committeeInvitationService).acceptInvitation(any(), any());
        assertThat(controller.acceptInvitation(request)).isNotNull();

        authenticateAs(PLATFORM_USER);
        doAnswer(servicePassingThrough(member, PLATFORM_USER))
                .when(committeeInvitationService).acceptInvitation(any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.acceptInvitation(request));
    }
}
