package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261021-1338 B5: パスキー登録・削除前の再認証用メールOTP 送信（手段が EMAIL_OTP の利用者だけが使える）。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("CMP-261021-1338 パスキー再認証用メールOTP送信")
class PasskeyManageReauthEmailOtpTest {

    private static final Long USER_ID = 1L;

    @Mock private TwoFactorAuthRepository twoFactorAuthRepository;
    @Mock private UserRepository userRepository;
    @Mock private WebAuthnCredentialRepository webAuthnCredentialRepository;
    @Mock private MfaEmailTwoFactorService mfaEmailTwoFactorService;

    @InjectMocks private Auth2faService auth2faService;

    private void userWithPassword(boolean hasPassword) {
        UserEntity user = UserEntity.builder()
                .email("otp@example.com")
                .passwordHash(hasPassword ? "$argon2id$hash" : null)
                .lastName("試験").firstName("太郎").displayName("otp")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(java.util.List.of());
        given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("SNSログインのみ（手段=EMAIL_OTP）は2FA未設定でも本人確認用メールOTPを送れる")
    void socialOnlyUserCanSend() {
        userWithPassword(false);
        MfaEmailSendResponse sent = new MfaEmailSendResponse("o***@example.com", 600, 60);
        given(mfaEmailTwoFactorService.sendReauthWithoutTwoFactor(USER_ID)).willReturn(sent);

        assertThat(auth2faService.sendPasskeyManageReauthEmailOtp(USER_ID).getData().getMaskedEmail())
                .isEqualTo("o***@example.com");
    }

    @Test
    @DisplayName("パスワードのある利用者は手段が EMAIL_OTP ではないので送れない（AUTH_080）")
    void passwordUserCannotSend() {
        userWithPassword(true);

        assertThatThrownBy(() -> auth2faService.sendPasskeyManageReauthEmailOtp(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_080));
        verify(mfaEmailTwoFactorService, never()).sendReauthWithoutTwoFactor(anyLong());
    }
}
