package com.mannschaft.app.social.controller;

import com.mannschaft.app.social.dto.FollowRequest;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.service.FollowService;
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
 * {@link FollowController} の運営アカウント所属禁止の配線試験（戦役 Z-A PR2 再検分指摘 1a）。
 *
 * <p>本物の {@link PlatformAccountAffiliationPolicy}（運営ロールの存在判定だけモック）で、
 * 「一般利用者は通る」「運営アカウントは 403 MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN」を確かめる。
 * Service は本番の取引内の拒否と同じ判定を、本番 Service が判定する利用者 ID に対して行うスタブ。
 * 方式の詳細は {@code PlatformAccountWiringSupport}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FollowController 運営アカウント所属禁止の配線")
class FollowControllerPlatformAccountWiringTest {

    @Spy
    private PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy = realPolicy();
    @Mock
    private FollowService followService;

    @InjectMocks
    private FollowController controller;

    @AfterEach
    void tearDown() {
        clearAuthentication();
    }

    @Test
    @DisplayName("フォロー: 一般利用者は通り、運営アカウントは 403")
    void follow() {
        FollowResponse ok = mock(FollowResponse.class);
        FollowRequest request = new FollowRequest("USER", 5L);
        authenticateAs(GENERAL_USER);
        doAnswer(servicePassingThrough(ok, GENERAL_USER)).when(followService).follow(any(), any(), any());
        assertThat(controller.follow(request).getStatusCode().value()).isEqualTo(201);

        authenticateAs(PLATFORM_USER);
        doAnswer(servicePassingThrough(ok, PLATFORM_USER)).when(followService).follow(any(), any(), any());
        assertForbiddenAsPlatformAccount(() -> controller.follow(request));
    }
}
