package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261123-1124 実機 D-2: 保護者招待は本人が同意待ち（PENDING_PARENTAL_CONSENT）のときだけ作れる。
 * 一般成人（ACTIVE）が /parental-consent/pending を開いて招待を送っても、同意リンクを作らずメールも積まない。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("保護者招待は同意待ちのときだけ（CMP-261123-1124 D-2）")
class ParentalConsentInviteStatusGuardTest {

    private static final Long USER_ID = 1L;

    @InjectMocks private ParentalConsentService service;
    @Mock private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private PendingInviteStore pendingInviteStore;

    private UserEntity user(UserEntity.UserStatus status) {
        return UserEntity.builder()
                .email("user@example.com").lastName("山田").firstName("太郎").displayName("たろう")
                .locale("ja").timezone("Asia/Tokyo").status(status)
                .isSearchable(true).birthDate("1990-01-01").build();
    }

    @Test
    @DisplayName("ACTIVE の利用者の招待は AUTH_087 で拒否し、リンクもメールも作らない")
    void activeUser_rejected() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));

        assertThatThrownBy(() -> service.inviteParent(USER_ID, "parent@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode()).isEqualTo("AUTH_087"));

        verify(parentalConsentLinkRepository, never()).save(any());
        verify(emailOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("同意待ちの利用者の招待は成功し、リンクとメールを作る")
    void pendingUser_succeeds() {
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(user(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT)));
        given(parentalConsentLinkRepository.countByChildUserIdAndStatus(USER_ID, ParentalConsentLinkStatus.PENDING))
                .willReturn(0L);
        given(authTokenService.hashToken(anyString())).willReturn("hashed");
        given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());

        service.inviteParent(USER_ID, "parent@example.com");

        verify(parentalConsentLinkRepository).save(any());
        verify(emailOutboxService).enqueue(any());
    }
}
