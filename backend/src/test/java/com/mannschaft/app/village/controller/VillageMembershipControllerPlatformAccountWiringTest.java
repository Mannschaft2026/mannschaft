package com.mannschaft.app.village.controller;

import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.MembershipResponse;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.VillageMembershipService;
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
 * {@link VillageMembershipController} の運営アカウント所属禁止の配線試験（戦役 Z-A PR2 再検分指摘 1a）。
 *
 * <p>本物の {@link PlatformAccountAffiliationPolicy}（運営ロールの存在判定だけモック）で、
 * 「一般利用者は通る」「運営アカウントは 403 MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN」を確かめる。
 * Service は本番の取引内の拒否と同じ判定を、本番 Service が判定する利用者 ID に対して行うスタブ。
 * 方式の詳細は {@code PlatformAccountWiringSupport}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VillageMembershipController 運営アカウント所属禁止の配線")
class VillageMembershipControllerPlatformAccountWiringTest {

    @Spy
    private PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy = realPolicy();
    @Mock
    private VillageMembershipService membershipService;
    @Mock
    private VillageSubjectAuthorizationGuard subjectAuthorizationGuard;

    @InjectMocks
    private VillageMembershipController controller;

    @AfterEach
    void tearDown() {
        clearAuthentication();
    }

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");

    @Test
    @DisplayName("村に参加: 操作者が一般利用者なら通り、運営アカウントなら 403")
    void joinJudgesActor() {
        MembershipResponse ok = mock(MembershipResponse.class);
        MembershipJoinRequest request = new MembershipJoinRequest(VillageSubjectType.USER, GENERAL_USER);
        authenticateAs(GENERAL_USER);
        doAnswer(servicePassingThrough(ok, GENERAL_USER, GENERAL_USER))
                .when(membershipService).join(any(), any(), any(), any());
        assertThat(controller.join(VILLAGE_ID, request).getStatusCode().value()).isEqualTo(201);

        authenticateAs(PLATFORM_USER);
        MembershipJoinRequest own = new MembershipJoinRequest(VillageSubjectType.USER, PLATFORM_USER);
        doAnswer(servicePassingThrough(ok, PLATFORM_USER, PLATFORM_USER))
                .when(membershipService).join(any(), any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.join(VILLAGE_ID, own));
    }

    @Test
    @DisplayName("村に参加: 一般利用者の操作者が USER 名義の主体として運営アカウントを入れようとしても 403")
    void joinJudgesUserSubject() {
        MembershipResponse ok = mock(MembershipResponse.class);
        authenticateAs(GENERAL_USER);
        MembershipJoinRequest request = new MembershipJoinRequest(VillageSubjectType.USER, PLATFORM_USER);
        doAnswer(servicePassingThrough(ok, GENERAL_USER, PLATFORM_USER))
                .when(membershipService).join(any(), any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.join(VILLAGE_ID, request));
    }
}
