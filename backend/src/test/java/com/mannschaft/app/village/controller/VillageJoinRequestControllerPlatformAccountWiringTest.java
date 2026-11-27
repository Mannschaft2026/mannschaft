package com.mannschaft.app.village.controller;

import com.mannschaft.app.village.dto.JoinRequestCreateRequest;
import com.mannschaft.app.village.dto.JoinRequestResponse;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.VillageJoinRequestService;
import com.mannschaft.app.village.service.VillageSubjectAuthorizationGuard;
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
 * {@link VillageJoinRequestController} の運営アカウント所属禁止の配線試験（戦役 Z-A PR2 再検分指摘 1a）。
 *
 * <p>本物の {@link PlatformAccountAffiliationPolicy}（運営ロールの存在判定だけモック）で、
 * 「一般利用者は通る」「運営アカウントは 403 MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN」を確かめる。
 * Service は本番の取引内の拒否と同じ判定を、本番 Service が判定する利用者 ID に対して行うスタブ。
 * 方式の詳細は {@code PlatformAccountWiringSupport}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VillageJoinRequestController 運営アカウント所属禁止の配線")
class VillageJoinRequestControllerPlatformAccountWiringTest {

    @Spy
    private PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy = realPolicy();
    @Mock
    private VillageJoinRequestService service;
    @Mock
    private VillageSubjectAuthorizationGuard subjectAuthorizationGuard;

    @InjectMocks
    private VillageJoinRequestController controller;

    @AfterEach
    void tearDown() {
        clearAuthentication();
    }

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000bb");

    @Test
    @DisplayName("村参加申請: 一般利用者は通り、運営アカウントは 403")
    void create() {
        JoinRequestResponse ok = mock(JoinRequestResponse.class);
        JoinRequestCreateRequest request =
                new JoinRequestCreateRequest(VillageSubjectType.USER, GENERAL_USER, "よろしく");
        authenticateAs(GENERAL_USER);
        doAnswer(servicePassingThrough(ok, GENERAL_USER)).when(service).createRequest(any(), any(), any(), any());
        assertThat(controller.create(VILLAGE_ID, request).getStatusCode().value()).isEqualTo(201);

        authenticateAs(PLATFORM_USER);
        doAnswer(servicePassingThrough(ok, PLATFORM_USER)).when(service).createRequest(any(), any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.create(VILLAGE_ID, request));
    }

    @Test
    @DisplayName("村参加申請の承認: 操作者（一般の村長）ではなく入村する申請者を判定する")
    void approveJudgesAdmittedApplicant() {
        JoinRequestResponse ok = mock(JoinRequestResponse.class);
        authenticateAs(GENERAL_USER);
        when(service.findAdmissionUserId(any(), any())).thenReturn(Optional.of(OTHER_GENERAL_USER));
        doAnswer(servicePassingThrough(ok, OTHER_GENERAL_USER)).when(service).approve(any(), any(), any(), any());
        assertThat(controller.approve(VILLAGE_ID, REQUEST_ID, null).getStatusCode().value()).isEqualTo(200);

        // 申請者が運営アカウント。操作者は一般利用者のまま
        when(service.findAdmissionUserId(any(), any())).thenReturn(Optional.of(PLATFORM_USER));
        doAnswer(servicePassingThrough(ok, PLATFORM_USER)).when(service).approve(any(), any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.approve(VILLAGE_ID, REQUEST_ID, null));
    }
}
