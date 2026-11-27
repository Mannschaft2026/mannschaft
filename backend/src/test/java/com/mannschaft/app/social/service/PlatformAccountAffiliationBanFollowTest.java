package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.social.SocialMapper;
import com.mannschaft.app.social.controller.FollowController;
import com.mannschaft.app.social.dto.FollowRequest;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.judged;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.platform;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2（マスター決定 2026-11-26「個人フォローも禁止」）: 運営アカウントは、タイムライン上の個人フォロー
 * （{@link FollowService#follow}）もできない。拒否は follows 行を作る前。
 *
 * <p>判定（user_roles の読み取り）は {@link FollowController} が social の取引の外で済ませ、
 * {@link FollowService#follow} は取引の中で判定結果だけを確かめる（D-3T）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 個人フォロー（戦役Z-A PR2 / P1・P5）")
class PlatformAccountAffiliationBanFollowTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final Long TARGET_ID = 200L;

    @Mock FollowRepository followRepository;
    @Mock SocialMapper socialMapper;
    @Mock UserRoleRepository userRoleRepository;
    @InjectMocks FollowService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("P1: 運営アカウントと判定された利用者の個人フォローは 403・follows を作らない")
    void P1_運営アカウントは個人フォローできない() {
        assertThatThrownBy(() -> judged(platform(PLATFORM_USER_ID),
                () -> service.follow("USER", TARGET_ID, PLATFORM_USER_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        verify(followRepository, never()).save(any(FollowEntity.class));
    }

    @Test
    @DisplayName("P5: 一般利用者のフォローも判定を通る（取引の外の判定が無ければ判定漏れとして止め、follows を作らない）")
    void P5_一般利用者のフォローもPolicyを通る() {
        assertThatThrownBy(() -> service.follow("USER", TARGET_ID, GENERAL_USER_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(followRepository, never()).save(any(FollowEntity.class));
    }

    @Test
    @DisplayName("P1/D-3T: Controller が取引の外で user_roles を判定し、運営アカウントのフォローを 403 にする（一般利用者は通る）")
    void Controllerが取引の外で判定する() {
        PlatformAccountAffiliationPolicy policy = new PlatformAccountAffiliationPolicy(userRoleRepository);
        FollowController controller = new FollowController(service, policy);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(GENERAL_USER_ID)).willReturn(0L);
        given(followRepository.save(any(FollowEntity.class))).willAnswer(inv -> inv.getArgument(0));

        authenticateAs(PLATFORM_USER_ID);
        assertThatThrownBy(() -> controller.follow(new FollowRequest("USER", TARGET_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        verify(followRepository, never()).save(any(FollowEntity.class));

        authenticateAs(GENERAL_USER_ID);
        controller.follow(new FollowRequest("USER", TARGET_ID));
        verify(followRepository).save(any(FollowEntity.class));
    }

    private static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
