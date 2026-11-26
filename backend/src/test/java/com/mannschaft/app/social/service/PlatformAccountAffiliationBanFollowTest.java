package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.social.SocialMapper;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2（マスター決定 2026-11-26「個人フォローも禁止」）: 運営アカウントは、タイムライン上の個人フォロー
 * （{@link FollowService#follow}）もできない。拒否は follows 行を作る前。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 個人フォロー（戦役Z-A PR2 / P1・P5）")
class PlatformAccountAffiliationBanFollowTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final Long TARGET_ID = 200L;

    @Mock PlatformAccountAffiliationPolicy policy;
    @Mock FollowRepository followRepository;
    @Mock SocialMapper socialMapper;
    @InjectMocks FollowService service;

    @Test
    @DisplayName("P1: 運営アカウントの個人フォローは 403・follows を作らない")
    void P1_運営アカウントは個人フォローできない() {
        doThrow(new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN))
                .when(policy).assertAffiliatable(PLATFORM_USER_ID);

        assertThatThrownBy(() -> service.follow("USER", TARGET_ID, PLATFORM_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        verify(followRepository, never()).save(any(FollowEntity.class));
    }

    @Test
    @DisplayName("P5: 一般利用者のフォローも Policy を通る")
    void P5_一般利用者のフォローもPolicyを通る() {
        catchThrowable(() -> service.follow("USER", TARGET_ID, GENERAL_USER_ID));
        verify(policy).assertAffiliatable(GENERAL_USER_ID);
    }
}
