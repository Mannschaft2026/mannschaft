package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.event.DomainEvent;
import com.mannschaft.app.common.util.SessionHashUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * CMP-261012-0911 試練: 2FA 完了後のセッション発行を {@link AuthService} の共通発行器へ寄せる（判断③・AC-15）。
 *
 * <p>{@code completeMfaLogin} は package-private（パスキーの {@code completePasskeyLogin} と同じ）のため、
 * 同パッケージの本クラスで検査する。HTTP 境界の確認は {@code OAuthMfaLoginIT} が担う。</p>
 *
 * <p>対象: AC-2/AC-6（LOGIN_SUCCESS の ip/UA・sessionHash=SHA-256(jti)・method）、AC-15（新端末検知・セッション上限・
 * 有効期限・last_login_at・LOGIN_SUCCESS 1 件・roles はサーバ側解決）、判断①（method=入口方式）、判断②（使用要素）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261012-0911 AuthService.completeMfaLogin（2FA 完了後の共通セッション発行）")
class AuthServiceMfaCompletionTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private TwoFactorAuthRepository twoFactorAuthRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private NewDeviceDetectionService newDeviceDetectionService;
    @Mock private RoleClaimResolver roleClaimResolver;
    @Mock private StatusClaimResolver statusClaimResolver;
    @Mock private AuthRegistrationService authRegistrationService;
    @Mock private AuthSessionService authSessionService;
    @Mock private AuthTokenRotationService authTokenRotationService;
    @Mock private AuthPasswordResetService authPasswordResetService;

    @InjectMocks
    private AuthService authService;

    private static final Long USER_ID = 42L;
    private static final String IP = "203.0.113.77";
    private static final String UA = "MfaCompletion/1.0";
    private static final long REFRESH_SECONDS = 2_592_000L;

    private UserEntity user() {
        UserEntity u = UserEntity.builder()
                .email("mfa-complete@example.com")
                .passwordHash(null)
                .lastName("山田").firstName("太郎").displayName("yamada")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    private void stubIssue() {
        lenient().when(roleClaimResolver.resolveRoles(USER_ID)).thenReturn(List.of("MEMBER"));
        lenient().when(statusClaimResolver.isPendingParentalConsent(USER_ID)).thenReturn(false);
        lenient().when(authTokenService.issueAccessToken(eq(USER_ID), any(), eq(false))).thenReturn("access-jwt");
        lenient().when(authTokenService.generateRefreshToken()).thenReturn("raw-refresh");
        lenient().when(authTokenService.hashToken(anyString())).thenAnswer(inv -> "h-" + inv.getArgument(0));
        lenient().when(authTokenService.getRefreshTokenExpirationSeconds()).thenReturn(REFRESH_SECONDS);
        lenient().when(authTokenService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        lenient().when(refreshTokenRepository.save(any(RefreshTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<LoginSuccessEvent> loginSuccessEvents() {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream()
                .filter(LoginSuccessEvent.class::isInstance)
                .map(LoginSuccessEvent.class::cast)
                .toList();
    }

    private RefreshTokenEntity savedRefreshToken() {
        ArgumentCaptor<RefreshTokenEntity> captor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("AC-2/判断①②: OAuth 由来の 2FA 完了は method=OAUTH_GOOGLE・使用要素 TOTP・ip/UA・sessionHash=SHA-256(jti) の LOGIN_SUCCESS を 1 件だけ出す")
    void completeMfaLogin_OAuth入口_LoginSuccess() {
        stubIssue();

        TokenResponse tokens = authService.completeMfaLogin(user(), IP, UA, "OAUTH_GOOGLE", MfaVerificationMethod.TOTP, List.of("MEMBER"));

        assertThat(tokens).as("トークンが返る").isNotNull();
        assertThat(tokens.getAccessToken()).isEqualTo("access-jwt");
        assertThat(tokens.getRefreshToken()).isEqualTo("raw-refresh");
        List<LoginSuccessEvent> events = loginSuccessEvents();
        assertThat(events).as("AC-15 LOGIN_SUCCESS は重複しない").hasSize(1);
        LoginSuccessEvent e = events.get(0);
        assertThat(e.getUserId()).isEqualTo(USER_ID);
        assertThat(e.getIpAddress()).isEqualTo(IP);
        assertThat(e.getUserAgent()).isEqualTo(UA);
        assertThat(e.getMethod()).as("判断①: method は入口方式").isEqualTo("OAUTH_GOOGLE");
        assertThat(e.getMfaFactor()).as("判断②: 使用要素").isEqualTo("TOTP");
        assertThat(e.getSessionHash()).isEqualTo(SessionHashUtil.hash(savedRefreshToken().getJti()));
    }

    @Test
    @DisplayName("AC-6/判断①: パスワード由来の 2FA 完了は method=PASSWORD・使用要素 BACKUP_CODE")
    void completeMfaLogin_PASSWORD入口() {
        stubIssue();

        authService.completeMfaLogin(user(), IP, UA, "PASSWORD", MfaVerificationMethod.BACKUP_CODE, List.of("MEMBER"));

        LoginSuccessEvent e = loginSuccessEvents().get(0);
        assertThat(e.getMethod()).isEqualTo("PASSWORD");
        assertThat(e.getMfaFactor()).isEqualTo("BACKUP_CODE");
        assertThat(e.getSessionHash()).isNotNull();
        assertThat(e.getIpAddress()).isEqualTo(IP);
        assertThat(e.getUserAgent()).isEqualTo(UA);
    }

    @Test
    @DisplayName("AC-15: 2FA 経由でもパスワード直経路と同じく ip/UA/端末指紋/有効期限を保存し、新端末検知とセッション上限を適用する")
    void completeMfaLogin_副作用がパスワード直経路と同じ() {
        stubIssue();
        UserEntity u = user();

        authService.completeMfaLogin(u, IP, UA, "OAUTH_LINE", MfaVerificationMethod.EMAIL, List.of("MEMBER"));

        RefreshTokenEntity saved = savedRefreshToken();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getIpAddress()).isEqualTo(IP);
        assertThat(saved.getUserAgent()).isEqualTo(UA);
        assertThat(saved.getJti()).isNotBlank();
        assertThat(saved.getDeviceFingerprint()).as("端末指紋（UA 由来）").isEqualTo("h-" + UA);
        assertThat(saved.getExpiresAt()).as("有効期限はパスワード直経路と同じ設定値")
                .isCloseTo(LocalDateTime.now().plusSeconds(REFRESH_SECONDS), within(60, ChronoUnit.SECONDS));
        verify(newDeviceDetectionService).publishIfNewDeviceBeforeTokenSave(
                eq(USER_ID), eq(IP), eq("h-" + UA), any(), eq("ja"));
        verify(authSessionService).enforceMaxActiveSessions(USER_ID);
        assertThat(u.getLastLoginAt()).as("last_login_at を更新").isNotNull();
    }

    @Test
    @DisplayName("AC-15: roles は呼び出し元がサーバ側で解決した値を受け取り、自身は RoleClaimResolver へ越境しない（D-3T）")
    void completeMfaLogin_rolesはサーバ側解決() {
        stubIssue();

        authService.completeMfaLogin(user(), IP, UA, "OAUTH_APPLE", MfaVerificationMethod.TOTP, List.of("MEMBER"));

        verify(roleClaimResolver, org.mockito.Mockito.never()).resolveRoles(any());
        verify(authTokenService).issueAccessToken(USER_ID, List.of("MEMBER"), false);
    }
}
