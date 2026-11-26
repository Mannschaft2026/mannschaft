package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * CMP-261123-1125 試練 AC5: 保護者招待は、宛先がシステム登録済みか未登録かで応答を分岐させない
 * （例外・戻り値・送信の有無が同じ。存在オラクルを増やさない）。DTO の命名修正の回帰ガード。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("保護者招待の存在オラクル防止（CMP-261123-1125 AC5）")
class ParentalConsentInviteNoExistenceOracleTest {

    private static final Long CHILD_USER_ID = 1L;

    @InjectMocks
    private ParentalConsentService parentalConsentService;

    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private EmailOutboxService emailOutboxService;
    @Mock
    private PendingInviteStore pendingInviteStore;

    private void givenChildCanInvite(String parentEmail) {
        UserEntity child = UserEntity.builder()
                .email("child@example.com").lastName("山田").firstName("太郎").displayName("たろう")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT)
                .isSearchable(true).birthDate("2012-01-01").build();
        given(userRepository.findById(CHILD_USER_ID)).willReturn(Optional.of(child));
        given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                CHILD_USER_ID, ParentalConsentLinkStatus.PENDING)).willReturn(0L);
        given(parentalConsentLinkRepository.existsByChildUserIdAndParentEmailAndStatus(
                CHILD_USER_ID, parentEmail, ParentalConsentLinkStatus.PENDING)).willReturn(false);
        given(authTokenService.hashToken(anyString())).willReturn("hashed");
        given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());
    }

    @Test
    @DisplayName("AC5-7: 登録済みの保護者宛て — 例外なく招待メールを積む")
    void registeredParent_noBranch() {
        String email = "registered@example.com";
        givenChildCanInvite(email);
        UserEntity parent = UserEntity.builder()
                .email(email).lastName("山田").firstName("花子").displayName("はなこ")
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true).birthDate("1980-01-01").build();
        given(userRepository.findByEmail(email)).willReturn(Optional.of(parent));

        assertThatCode(() -> parentalConsentService.inviteParent(CHILD_USER_ID, email)).doesNotThrowAnyException();
        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService).enqueue(captor.capture());
        assertThat(captor.getValue().toAddress()).isEqualTo(email);
        assertThat(captor.getValue().templateKind()).isEqualTo("PARENTAL_CONSENT_INVITATION");
    }

    @Test
    @DisplayName("AC5-7: 未登録の保護者宛て — 登録済みと同じく例外なく招待メールを積む")
    void unregisteredParent_noBranch() {
        String email = "unknown@example.com";
        givenChildCanInvite(email);
        given(userRepository.findByEmail(email)).willReturn(Optional.empty());

        assertThatCode(() -> parentalConsentService.inviteParent(CHILD_USER_ID, email)).doesNotThrowAnyException();
        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService).enqueue(captor.capture());
        assertThat(captor.getValue().toAddress()).isEqualTo(email);
        assertThat(captor.getValue().templateKind()).isEqualTo("PARENTAL_CONSENT_INVITATION");
    }
}
