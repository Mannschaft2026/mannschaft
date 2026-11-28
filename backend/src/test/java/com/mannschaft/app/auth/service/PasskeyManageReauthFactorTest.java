package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.PasskeyManageReauthMethod;
import com.mannschaft.app.auth.ReauthPurpose;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.dto.PasskeyManageReauthRequest;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.event.ReauthenticationFailedEvent;
import com.mannschaft.app.auth.event.ReauthenticationSucceededEvent;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.event.DomainEvent;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役A「パスキー再認証（CMP-261021-1338）」B5〜B7 の受け入れ試練（BE 単体・{@link Auth2faService}）。
 *
 * <p>パスキーを持たない利用者の登録・削除前再認証は、{@link Auth2faService#disableTwoFactor} と同じ正規形
 * （パスワード保有者はパスワード＋有効な 2FA 要素、いずれも無ければ本人確認用メールOTP＝REAUTH）で行う。
 * パスキー保有者はパスキー再認証（UV 必須）しか使えない。何も確認せず通る経路を作らない。</p>
 *
 * <p>前提にした API 形状（出陣への申し送り）:</p>
 * <ul>
 *   <li>{@code Auth2faService#resolvePasskeyManageReauthMethod(userId)} → {@link PasskeyManageReauthMethod}</li>
 *   <li>{@code Auth2faService#reauthenticateForPasskeyManage(userId, sessionHash, PasskeyManageReauthRequest)}。
 *       成功時 {@code AuthWebAuthnService#markReauthenticated(userId, sessionHash, PASSKEY_MANAGE)}</li>
 *   <li>パスキー保有判定は {@code WebAuthnCredentialRepository#findByUserId}（本試練はこれをスタブする）</li>
 *   <li>2FA 行は {@code findByUserIdForUpdate} / {@code findByUserId} のどちらで読んでもよい（両方スタブ済み）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261021-1338 B5〜B7 パスキー登録・削除前の再認証（パスワード／2FA／メールOTP 経路）")
class PasskeyManageReauthFactorTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION = "c".repeat(64);
    private static final String PASSWORD = "Correct-Passw0rd!";
    private static final String PASSWORD_HASH = "$argon2id$hash";
    private static final String OTP = "123456";

    @Mock private TwoFactorAuthRepository twoFactorAuthRepository;
    @Mock private UserRepository userRepository;
    @Mock private MfaRecoveryTokenRepository mfaRecoveryTokenRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private EncryptionService encryptionService;
    @Mock private RoleClaimResolver roleClaimResolver;
    @Mock private StatusClaimResolver statusClaimResolver;
    @Mock private MfaSessionService mfaSessionService;
    @Mock private MfaEmailOtpService mfaEmailOtpService;
    @Mock private MfaFailureAuditRecorder mfaFailureAuditRecorder;
    @Mock private MfaEmailTwoFactorService mfaEmailTwoFactorService;
    @Mock private AuthService authService;
    @Mock private Clock wallClock;
    // CMP-261021-1338 で Auth2faService に加わる想定の依存
    @Mock private WebAuthnCredentialRepository webAuthnCredentialRepository;
    @Mock private AuthWebAuthnService authWebAuthnService;

    @InjectMocks private Auth2faService auth2faService;

    /** 利用者の状態（B5 の分岐軸）。 */
    enum Holder {
        /** パスキー保有（パスワードあり）。 */
        PASSKEY,
        /** パスワードのみ（2FA 未設定・パスキー無し）。 */
        PASSWORD_ONLY,
        /** パスワード＋メール方式 2FA 有効（パスキー無し）。 */
        PASSWORD_AND_2FA,
        /** SNS ログインのみ（パスワード・2FA・パスキーいずれも無し）。 */
        SOCIAL_ONLY,
        /** SNS ログイン＋TOTP 方式 2FA 有効（パスワード・パスキー無し）。 */
        SOCIAL_AND_TOTP,
        /** SNS ログイン＋メール方式 2FA 有効（パスワード・パスキー無し）。 */
        SOCIAL_AND_EMAIL_2FA
    }

    private static final String BACKUP_CODE = "12345678";

    @BeforeEach
    void 準備() {
        given(wallClock.instant()).willReturn(Instant.parse("2026-11-25T03:00:00Z"));
        given(wallClock.getZone()).willReturn(ZoneOffset.UTC);
        given(passwordEncoder.matches(eq(PASSWORD), eq(PASSWORD_HASH))).willReturn(true);
        given(mfaEmailOtpService.verifyAndConsume(eq(MfaEmailOtpPurpose.REAUTH), anyString(), eq(OTP)))
                .willReturn(true);
    }

    private void as(Holder holder) {
        UserEntity user = UserEntity.builder()
                .email("reauth-factor@example.com")
                .passwordHash(holder == Holder.SOCIAL_ONLY || holder == Holder.SOCIAL_AND_TOTP
                        || holder == Holder.SOCIAL_AND_EMAIL_2FA ? null : PASSWORD_HASH)
                .lastName("試練").firstName("太郎").displayName("再認証試練")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        Optional<TwoFactorAuthEntity> twoFactor = switch (holder) {
            case PASSWORD_AND_2FA, SOCIAL_AND_EMAIL_2FA -> Optional.of(TwoFactorAuthEntity.builder()
                    .userId(USER_ID).method(TwoFactorMethod.EMAIL).backupCodes("[]").isEnabled(true).build());
            case SOCIAL_AND_TOTP -> Optional.of(TwoFactorAuthEntity.builder()
                    .userId(USER_ID).method(TwoFactorMethod.TOTP).backupCodes("[\"backup-hash\"]")
                    .isEnabled(true).build());
            default -> Optional.empty();
        };
        given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(twoFactor);
        given(twoFactorAuthRepository.findByUserIdForUpdate(USER_ID)).willReturn(twoFactor);

        List<WebAuthnCredentialEntity> passkeys = holder == Holder.PASSKEY
                ? List.of(WebAuthnCredentialEntity.builder().id(10L).userId(USER_ID).credentialId("cred")
                        .publicKey("pk").signCount(0L).build())
                : List.of();
        given(webAuthnCredentialRepository.findByUserId(USER_ID)).willReturn(passkeys);
    }

    private void reauth(PasskeyManageReauthRequest req) {
        auth2faService.reauthenticateForPasskeyManage(USER_ID, SESSION, req);
    }

    private static PasskeyManageReauthRequest req(String password, MfaVerificationMethod method, String code) {
        return new PasskeyManageReauthRequest(password, method, code);
    }

    private static void assertAuth080(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_080));
    }

    private void assertFlagMarked() {
        verify(authWebAuthnService).markReauthenticated(USER_ID, SESSION, ReauthPurpose.PASSKEY_MANAGE);
    }

    private void assertFlagNotMarked() {
        verify(authWebAuthnService, never()).markReauthenticated(any(), any(), any());
    }

    @Nested
    @DisplayName("B5 手段の自動選択")
    class ResolveMethod {

        @Test
        void B5_パスキー保有者の再認証手段はパスキー() {
            as(Holder.PASSKEY);
            assertThat(auth2faService.resolvePasskeyManageReauthMethod(USER_ID))
                    .isEqualTo(PasskeyManageReauthMethod.PASSKEY);
        }

        @Test
        void B5_パスワードのみの利用者の再認証手段はパスワード() {
            as(Holder.PASSWORD_ONLY);
            assertThat(auth2faService.resolvePasskeyManageReauthMethod(USER_ID))
                    .isEqualTo(PasskeyManageReauthMethod.PASSWORD);
        }

        @Test
        void B5_2FA有効の利用者の再認証手段はパスワードと2要素() {
            as(Holder.PASSWORD_AND_2FA);
            assertThat(auth2faService.resolvePasskeyManageReauthMethod(USER_ID))
                    .isEqualTo(PasskeyManageReauthMethod.PASSWORD_AND_SECOND_FACTOR);
        }

        @Test
        void B5_SNSログインのみの利用者の再認証手段はメールOTP() {
            as(Holder.SOCIAL_ONLY);
            assertThat(auth2faService.resolvePasskeyManageReauthMethod(USER_ID))
                    .isEqualTo(PasskeyManageReauthMethod.EMAIL_OTP);
        }
    }

    @Nested
    @DisplayName("B5 手段ごとの照合")
    class Verify {

        @Test
        void B5_SNS且つ2FA有効の利用者の再認証手段は2要素のみ() {
            as(Holder.SOCIAL_AND_TOTP);
            assertThat(auth2faService.resolvePasskeyManageReauthMethod(USER_ID))
                    .isEqualTo(PasskeyManageReauthMethod.SECOND_FACTOR);
        }

        @Test
        void B5_SNS且つTOTP有効の利用者はメールOTPだけではAUTH080() {
            as(Holder.SOCIAL_AND_TOTP);
            assertAuth080(() -> reauth(req(null, MfaVerificationMethod.EMAIL, OTP)));
            assertFlagNotMarked();
        }

        @Test
        void B5_SNS且つTOTP有効の利用者は2FA要素のバックアップコードで再認証できる() {
            as(Holder.SOCIAL_AND_TOTP);
            given(passwordEncoder.matches(eq(BACKUP_CODE), eq("backup-hash"))).willReturn(true);
            reauth(req(null, MfaVerificationMethod.BACKUP_CODE, BACKUP_CODE));
            assertFlagMarked();
        }

        @Test
        void B5_SNS且つメール2FA有効の利用者は2FAとしてのメールOTPで再認証できる() {
            as(Holder.SOCIAL_AND_EMAIL_2FA);
            reauth(req(null, MfaVerificationMethod.EMAIL, OTP));
            assertFlagMarked();
        }

        @Test
        void B5_パスキー保有者は正しいパスワードでもこの経路で再認証できない() {
            as(Holder.PASSKEY);
            assertAuth080(() -> reauth(req(PASSWORD, null, null)));
            assertFlagNotMarked();
        }

        @Test
        void B5_パスワードのみの利用者は正しいパスワードで再認証できる() {
            as(Holder.PASSWORD_ONLY);
            reauth(req(PASSWORD, null, null));
            assertFlagMarked();
        }

        @Test
        void B5_パスワードのみの利用者は誤ったパスワードでAUTH080() {
            as(Holder.PASSWORD_ONLY);
            assertAuth080(() -> reauth(req("wrong", null, null)));
            assertFlagNotMarked();
        }

        @Test
        void B5_2FA有効の利用者はパスワードだけではAUTH080() {
            as(Holder.PASSWORD_AND_2FA);
            assertAuth080(() -> reauth(req(PASSWORD, null, null)));
            assertFlagNotMarked();
        }

        @Test
        void B5_2FA有効の利用者は要素だけではAUTH080() {
            as(Holder.PASSWORD_AND_2FA);
            assertAuth080(() -> reauth(req(null, MfaVerificationMethod.EMAIL, OTP)));
            assertFlagNotMarked();
        }

        @Test
        void B5_2FA有効の利用者はパスワードと本人確認用メールOTPの両方で再認証できる() {
            as(Holder.PASSWORD_AND_2FA);
            reauth(req(PASSWORD, MfaVerificationMethod.EMAIL, OTP));
            assertFlagMarked();
            verify(mfaEmailOtpService).verifyAndConsume(eq(MfaEmailOtpPurpose.REAUTH), anyString(), eq(OTP));
        }

        @Test
        void B5_SNSログインのみの利用者は本人確認用メールOTPで再認証できる() {
            as(Holder.SOCIAL_ONLY);
            reauth(req(null, MfaVerificationMethod.EMAIL, OTP));
            assertFlagMarked();
            verify(mfaEmailOtpService).verifyAndConsume(eq(MfaEmailOtpPurpose.REAUTH), anyString(), eq(OTP));
        }

        @Test
        void B5_SNSログインのみの利用者は誤ったメールOTPでAUTH080() {
            as(Holder.SOCIAL_ONLY);
            assertAuth080(() -> reauth(req(null, MfaVerificationMethod.EMAIL, "000000")));
            assertFlagNotMarked();
        }

        @ParameterizedTest
        @EnumSource(Holder.class)
        void B5_何も提示しない再認証はどの利用者でも通らない(Holder holder) {
            as(holder);
            assertAuth080(() -> reauth(req(null, null, null)));
            assertAuth080(() -> reauth(req("", null, "")));
            assertThatThrownBy(() -> reauth(null)).isInstanceOf(BusinessException.class);
            assertFlagNotMarked();
        }

        @Test
        void B4_セッションを特定できない要求では再認証フラグを立てない() {
            as(Holder.PASSWORD_ONLY);
            assertThatThrownBy(() -> auth2faService.reauthenticateForPasskeyManage(USER_ID, null,
                    req(PASSWORD, null, null))).isInstanceOf(BusinessException.class);
            verify(authWebAuthnService, never()).markReauthenticated(any(), isNull(), any());
        }
    }

    @Nested
    @DisplayName("B6 レート制限 / B7 監査")
    class RateLimitAndAudit {

        @Test
        void B6_再認証は5回15分のレート制限を照合より先に掛ける() {
            as(Holder.PASSWORD_ONLY);
            reauth(req(PASSWORD, null, null));
            verify(authTokenService).checkRateLimit(startsWith("mannschaft:auth:"), eq(5), eq(Duration.ofMinutes(15)));
        }

        @Test
        void B6_レート制限超過時はパスワードを照合せずフラグを立てない() {
            as(Holder.PASSWORD_ONLY);
            willThrow(new BusinessException(AuthErrorCode.AUTH_044))
                    .given(authTokenService).checkRateLimit(startsWith("mannschaft:auth:"), eq(5), eq(Duration.ofMinutes(15)));

            assertThatThrownBy(() -> reauth(req(PASSWORD, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.AUTH_044));
            verify(passwordEncoder, never()).matches(any(), any());
            assertFlagNotMarked();
        }

        @Test
        void B7_パスワード再認証の成功を監査イベントで記録する() {
            as(Holder.PASSWORD_ONLY);
            reauth(req(PASSWORD, null, null));

            ReauthenticationSucceededEvent event = published(ReauthenticationSucceededEvent.class);
            assertThat(event.getUserId()).isEqualTo(USER_ID);
            assertThat(event.getPurpose()).isEqualTo(ReauthPurpose.PASSKEY_MANAGE);
            assertThat(event.getMethod()).isEqualTo(PasskeyManageReauthMethod.PASSWORD);
        }

        @Test
        void B7_セッション不明の再認証も失敗として監査する() {
            as(Holder.PASSWORD_ONLY);
            assertThatThrownBy(() -> auth2faService.reauthenticateForPasskeyManage(USER_ID, null,
                    req(PASSWORD, null, null))).isInstanceOf(BusinessException.class);
            assertThat(published(ReauthenticationFailedEvent.class).getPurpose())
                    .isEqualTo(ReauthPurpose.PASSKEY_MANAGE);
        }

        @Test
        void B7_レート制限超過AUTH044も失敗として監査する() {
            as(Holder.PASSWORD_ONLY);
            willThrow(new BusinessException(AuthErrorCode.AUTH_044))
                    .given(authTokenService).checkRateLimit(startsWith("mannschaft:auth:"), eq(5), eq(Duration.ofMinutes(15)));
            assertThatThrownBy(() -> reauth(req(PASSWORD, null, null))).isInstanceOf(BusinessException.class);
            assertThat(published(ReauthenticationFailedEvent.class).getPurpose())
                    .isEqualTo(ReauthPurpose.PASSKEY_MANAGE);
        }

        @Test
        void B7_メールOTP再認証の失敗を監査イベントで記録する() {
            as(Holder.SOCIAL_ONLY);
            assertAuth080(() -> reauth(req(null, MfaVerificationMethod.EMAIL, "000000")));

            ReauthenticationFailedEvent event = published(ReauthenticationFailedEvent.class);
            assertThat(event.getUserId()).isEqualTo(USER_ID);
            assertThat(event.getPurpose()).isEqualTo(ReauthPurpose.PASSKEY_MANAGE);
            assertThat(event.getMethod()).isEqualTo(PasskeyManageReauthMethod.EMAIL_OTP);
        }
    }

    private <T> T published(Class<T> type) {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " が発行されていない: "
                        + captor.getAllValues()));
    }
}
