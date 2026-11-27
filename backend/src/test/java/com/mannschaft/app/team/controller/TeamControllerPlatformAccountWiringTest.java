package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.supporter.dto.BulkApproveRequest;
import com.mannschaft.app.supporter.dto.FollowStatusResponse;
import com.mannschaft.app.supporter.service.SupporterService;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.service.TeamService;
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
 * {@link TeamController} の運営アカウント所属禁止の配線試験（戦役 Z-A PR2 再検分指摘 1a）。
 *
 * <p>本物の {@link PlatformAccountAffiliationPolicy}（運営ロールの存在判定だけモック）で、
 * 「一般利用者は通る」「運営アカウントは 403 MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN」を確かめる。
 * 承認系は<b>申請者の ID</b>を判定していること（操作者の ID ではないこと）まで確かめる。
 * 方式の詳細は {@code PlatformAccountWiringSupport}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TeamController 運営アカウント所属禁止の配線")
class TeamControllerPlatformAccountWiringTest {

    @Spy
    private PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy = realPolicy();
    @Mock
    private TeamService teamService;
    @Mock
    private SupporterService supporterService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private TeamController controller;

    @AfterEach
    void tearDown() {
        clearAuthentication();
    }

    @Test
    @DisplayName("チーム作成: 一般利用者は通り、運営アカウントは 403")
    @SuppressWarnings("unchecked")
    void createTeam() {
        ApiResponse<TeamResponse> ok = mock(ApiResponse.class);
        CreateTeamRequest req = new CreateTeamRequest();
        authenticateAs(GENERAL_USER);
        doAnswer(servicePassingThrough(ok, GENERAL_USER)).when(teamService).createTeam(any(), any());
        assertThat(controller.createTeam(req).getStatusCode().value()).isEqualTo(201);

        authenticateAs(PLATFORM_USER);
        doAnswer(servicePassingThrough(ok, PLATFORM_USER)).when(teamService).createTeam(any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.createTeam(req));
    }

    @Test
    @DisplayName("サポーター申請（フォロー）: 一般利用者は通り、運営アカウントは 403")
    @SuppressWarnings("unchecked")
    void follow() {
        ApiResponse<FollowStatusResponse> ok = mock(ApiResponse.class);
        authenticateAs(GENERAL_USER);
        doAnswer(servicePassingThrough(ok, GENERAL_USER)).when(supporterService).follow(any(), any(), any());
        assertThat(controller.followTeam("slug").getStatusCode().value()).isEqualTo(201);

        authenticateAs(PLATFORM_USER);
        doAnswer(servicePassingThrough(ok, PLATFORM_USER)).when(supporterService).follow(any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.followTeam("slug"));
    }

    @Test
    @DisplayName("サポーター個別承認: 操作者（一般の管理者）ではなく申請者を判定する")
    void approveJudgesApplicant() {
        authenticateAs(GENERAL_USER);
        when(supporterService.findApplicantUserIds(any())).thenReturn(List.of(OTHER_GENERAL_USER));
        doAnswer(servicePassingThrough(null, OTHER_GENERAL_USER)).when(supporterService).approve(any(), any(), any());
        assertThat(controller.approveSupporterApplication("slug", 1L).getStatusCode().value()).isEqualTo(204);

        // 申請者が運営アカウント。操作者は一般利用者のまま
        when(supporterService.findApplicantUserIds(any())).thenReturn(List.of(PLATFORM_USER));
        doAnswer(servicePassingThrough(null, PLATFORM_USER)).when(supporterService).approve(any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.approveSupporterApplication("slug", 1L));
    }

    @Test
    @DisplayName("サポーター一括承認: 申請者全員を判定し、1 人でも運営アカウントなら 403")
    void bulkApproveJudgesAllApplicants() {
        BulkApproveRequest request = mock(BulkApproveRequest.class);
        when(request.getApplicationIds()).thenReturn(List.of(1L, 2L));
        authenticateAs(GENERAL_USER);
        when(supporterService.findApplicantUserIds(any())).thenReturn(List.of(OTHER_GENERAL_USER, GENERAL_USER));
        doAnswer(servicePassingThrough(null, OTHER_GENERAL_USER, GENERAL_USER))
                .when(supporterService).bulkApprove(any(), any(), any());
        assertThat(controller.bulkApproveSupporterApplications("slug", request).getStatusCode().value())
                .isEqualTo(204);

        when(supporterService.findApplicantUserIds(any())).thenReturn(List.of(OTHER_GENERAL_USER, PLATFORM_USER));
        doAnswer(servicePassingThrough(null, OTHER_GENERAL_USER, PLATFORM_USER))
                .when(supporterService).bulkApprove(any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.bulkApproveSupporterApplications("slug", request));
    }
}
