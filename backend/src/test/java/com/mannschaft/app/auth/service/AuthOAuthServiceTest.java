package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.auth.OAuthProperties;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link AuthOAuthService} の単体テスト。
 * OAuthプロバイダ連携によるログイン・アカウント連携・連携解除のロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthOAuthService 単体テスト")
class AuthOAuthServiceTest {

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private OAuthLinkTokenRepository oauthLinkTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private WebAuthnCredentialRepository webAuthnCredentialRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private OAuthProperties oAuthProperties;

    // 認可基盤完全根治 Phase 1: トークン発行時に roles を解決するヘルパ。
    @Mock
    private RoleClaimResolver roleClaimResolver;

    @Mock
    private StatusClaimResolver statusClaimResolver;

    // CMP-261012-0911: OAuth 経路の 2FA ゲート（2FA 設定の参照とチャレンジ発行）
    @Mock
    private com.mannschaft.app.auth.repository.TwoFactorAuthRepository twoFactorAuthRepository;

    @Mock
    private MfaSessionService mfaSessionService;

    @InjectMocks
    private AuthOAuthService authOAuthService;

    /** 連携確認の入口（TX を持たない。roles 解決は TX の外）。実 AuthOAuthService を束ねて従来の振る舞いを検証する。 */
    private AuthOAuthLinkageConfirmService linkageConfirm() {
        return new AuthOAuthLinkageConfirmService(authOAuthService, roleClaimResolver);
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";

    private UserEntity createActiveUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash("$2a$12$encodedPasswordHash")
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private UserEntity createOAuthOnlyUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(null)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private OAuthAccountEntity createGoogleOAuthAccount() {
        return OAuthAccountEntity.builder()
                .userId(TEST_USER_ID)
                .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                .providerUserId("google-user-123")
                .providerEmail(TEST_EMAIL)
                .build();
    }

    private OAuthAccountEntity createLineOAuthAccount() {
        return OAuthAccountEntity.builder()
                .userId(TEST_USER_ID)
                .provider(OAuthAccountEntity.OAuthProvider.LINE)
                .providerUserId("line-user-456")
                .providerEmail(TEST_EMAIL)
                .build();
    }

    // ========================================
    // loginWithOAuth
    // ※ fetchOAuthUserInfoは実装済み（Google/LINE/Apple）だが
    //   外部API呼び出し（WebClient）を伴うため、プロバイダ検証のみ単体テスト対象。
    //   OAuth認証フローの結合テストは別途実施する。
    // ========================================

    @Nested
    @DisplayName("loginWithOAuth")
    class LoginWithOAuth {

        @Test
        @DisplayName("異常系: 未サポートプロバイダでAUTH_028例外")
        void loginWithOAuth_未サポートプロバイダ_AUTH028例外() {
            // Given / When / Then
            assertThatThrownBy(() -> authOAuthService.loginWithOAuth(
                    "INVALID_PROVIDER", "auth-code", TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_028"));
        }
    }

    // ========================================
    // loginWithOAuth: LoginSuccessEvent の引数順（CMP-261020-1530）
    // 外部プロバイダは JDK 標準の HttpServer で偽装し、実 WebClient 経路を通す。
    // ========================================

    @Nested
    @DisplayName("loginWithOAuth の LoginSuccessEvent 引数")
    class LoginSuccessEventArguments {

        private com.sun.net.httpserver.HttpServer fakeProvider;

        @org.junit.jupiter.api.BeforeEach
        void startFakeProvider() throws java.io.IOException {
            fakeProvider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            fakeProvider.createContext("/token", ex -> respond(ex, "{\"access_token\":\"fake-at\"}"));
            fakeProvider.createContext("/userinfo", ex -> respond(ex,
                    "{\"sub\":\"google-sub-1\",\"email\":\"" + TEST_EMAIL + "\","
                            + "\"given_name\":\"太郎\",\"family_name\":\"山田\",\"name\":\"yamada\"}"));
            fakeProvider.start();
            String base = "http://127.0.0.1:" + fakeProvider.getAddress().getPort();
            given(oAuthProperties.getGoogleTokenUri()).willReturn(base + "/token");
            given(oAuthProperties.getGoogleUserinfoUri()).willReturn(base + "/userinfo");
            given(oAuthProperties.getGoogleRedirectUri()).willReturn("http://localhost/callback");
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(authOAuthService, "initWebClient");
        }

        @org.junit.jupiter.api.AfterEach
        void stopFakeProvider() {
            fakeProvider.stop(0);
        }

        private void respond(com.sun.net.httpserver.HttpExchange ex, String json) throws java.io.IOException {
            byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        private LoginSuccessEvent capturedLoginSuccess() {
            org.mockito.ArgumentCaptor<com.mannschaft.app.common.event.DomainEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(com.mannschaft.app.common.event.DomainEvent.class);
            verify(eventPublisher, atLeastOnce()).publish(captor.capture());
            return captor.getAllValues().stream()
                    .filter(LoginSuccessEvent.class::isInstance)
                    .map(LoginSuccessEvent.class::cast)
                    .findFirst().orElseThrow();
        }

        private void stubExistingLink() {
            OAuthAccountEntity account = OAuthAccountEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-sub-1")
                    .providerEmail(TEST_EMAIL)
                    .build();
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, "google-sub-1")).willReturn(Optional.of(account));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            stubTokenIssue();
        }

        private void stubTokenIssue() {
            given(authTokenService.issueAccessToken(any(), any(), anyBoolean())).willReturn("at");
            given(authTokenService.generateRefreshToken()).willReturn("rt");
            given(authTokenService.hashToken(any())).willReturn("hash");
            given(refreshTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        }

        private void stubNewUser() {
            given(oauthAccountRepository.findByProviderAndProviderUserId(any(), any())).willReturn(Optional.empty());
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());
            given(encryptionService.hmac(any())).willReturn("h");
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            stubTokenIssue();
        }

        @Test
        @DisplayName("AC-1: 既存連携ログインで ip=引数IP・UA=引数UA・method=OAUTH_GOOGLE")
        void 既存連携_引数順が正しい() {
            stubExistingLink();

            authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            LoginSuccessEvent event = capturedLoginSuccess();
            assertThat(event.getIpAddress()).isEqualTo(TEST_IP);
            assertThat(event.getUserAgent()).isEqualTo(TEST_USER_AGENT);
            assertThat(event.getMethod()).isEqualTo("OAUTH_GOOGLE");
        }

        @Test
        @DisplayName("AC-2: OAuth 新規ユーザー登録経路でも ip・UA・method が正しい位置に入る")
        void 新規登録_引数順が正しい() {
            stubNewUser();

            authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            LoginSuccessEvent event = capturedLoginSuccess();
            assertThat(event.getIpAddress()).isEqualTo(TEST_IP);
            assertThat(event.getUserAgent()).isEqualTo(TEST_USER_AGENT);
            assertThat(event.getMethod()).isEqualTo("OAUTH_GOOGLE");
        }

        @Test
        @DisplayName("AC-3: userAgent が null なら LoginSuccessEvent.userAgent も null のまま（既存連携）")
        void 既存連携_UAがnullならnullのまま() {
            stubExistingLink();

            authOAuthService.loginWithOAuth("google", "code", TEST_IP, null);

            LoginSuccessEvent event = capturedLoginSuccess();
            assertThat(event.getIpAddress()).isEqualTo(TEST_IP);
            assertThat(event.getUserAgent()).isNull();
            assertThat(event.getMethod()).isEqualTo("OAUTH_GOOGLE");
        }

        @Test
        @DisplayName("AC-3: userAgent が null なら LoginSuccessEvent.userAgent も null のまま（新規登録）")
        void 新規登録_UAがnullならnullのまま() {
            stubNewUser();

            authOAuthService.loginWithOAuth("google", "code", TEST_IP, null);

            LoginSuccessEvent event = capturedLoginSuccess();
            assertThat(event.getUserAgent()).isNull();
            assertThat(event.getIpAddress()).isEqualTo(TEST_IP);
        }

        private String capturedRefreshTokenJti() {
            org.mockito.ArgumentCaptor<com.mannschaft.app.auth.entity.RefreshTokenEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(com.mannschaft.app.auth.entity.RefreshTokenEntity.class);
            verify(refreshTokenRepository).save(captor.capture());
            return captor.getValue().getJti();
        }

        @Test
        @DisplayName("jti: 既存連携ログインで保存する RefreshToken の jti が非 null、sessionHash = SHA-256(jti)")
        void 既存連携_jtiが設定されsessionHashと一致する() {
            stubExistingLink();

            authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            String jti = capturedRefreshTokenJti();
            assertThat(jti).isNotNull();
            assertThat(capturedLoginSuccess().getSessionHash())
                    .isEqualTo(com.mannschaft.app.common.util.SessionHashUtil.hash(jti));
        }

        @Test
        @DisplayName("jti: 新規登録ログインで保存する RefreshToken の jti が非 null、sessionHash = SHA-256(jti)")
        void 新規登録_jtiが設定されsessionHashと一致する() {
            stubNewUser();

            authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            String jti = capturedRefreshTokenJti();
            assertThat(jti).isNotNull();
            assertThat(capturedLoginSuccess().getSessionHash())
                    .isEqualTo(com.mannschaft.app.common.util.SessionHashUtil.hash(jti));
        }
    }

    // ========================================
    // confirmOAuthLinkage
    // ========================================

    @Nested
    @DisplayName("confirmOAuthLinkage")
    class ConfirmOAuthLinkage {

        @Test
        @DisplayName("正常系: OAuth連携が完了しトークンが発行される")
        void confirmOAuthLinkage_正常_トークン発行() {
            // Given
            String rawToken = "link-token";
            String tokenHash = "hashed-link-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            OAuthLinkTokenEntity linkToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-123")
                    .providerEmail(TEST_EMAIL)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(oauthLinkTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(linkToken));
            given(oauthLinkTokenRepository.save(any(OAuthLinkTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            given(authTokenService.issueAccessToken(any(), any(), anyBoolean())).willReturn("jwt-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("raw-refresh-token");
            given(authTokenService.hashToken("raw-refresh-token")).willReturn("hashed-refresh-token");
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<?> response = linkageConfirm().confirmOAuthLinkage(rawToken, TEST_IP, TEST_USER_AGENT);

            // Then
            TokenResponse data = (TokenResponse) response.getData();
            assertThat(data.getAccessToken()).isEqualTo("jwt-access-token");
            assertThat(data.getRefreshToken()).isEqualTo("raw-refresh-token");
            verify(oauthAccountRepository).save(any(OAuthAccountEntity.class));
            // OAuthLinkedEvent + LoginSuccessEvent（CMP-261012-0911: 連携確認でも LOGIN_SUCCESS を出す）
            verify(eventPublisher, times(2)).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_031例外")
        void confirmOAuthLinkage_トークン不在_AUTH031例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(oauthLinkTokenRepository.findByTokenHash("hashed-nonexistent"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> linkageConfirm().confirmOAuthLinkage(rawToken, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }

        @Test
        @DisplayName("異常系: トークン期限切れでAUTH_031例外")
        void confirmOAuthLinkage_期限切れ_AUTH031例外() {
            // Given
            String rawToken = "expired-token";
            String tokenHash = "hashed-expired-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            OAuthLinkTokenEntity expiredToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-123")
                    .providerEmail(TEST_EMAIL)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().minusHours(1))
                    .build();
            given(oauthLinkTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> linkageConfirm().confirmOAuthLinkage(rawToken, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_031例外")
        void confirmOAuthLinkage_使用済み_AUTH031例外() {
            // Given
            String rawToken = "used-token";
            String tokenHash = "hashed-used-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            OAuthLinkTokenEntity usedToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-123")
                    .providerEmail(TEST_EMAIL)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            usedToken.markUsed();
            given(oauthLinkTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> linkageConfirm().confirmOAuthLinkage(rawToken, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }
    }

    // ========================================
    // getConnectedProviders
    // ========================================

    @Nested
    @DisplayName("getConnectedProviders")
    class GetConnectedProviders {

        @Test
        @DisplayName("正常系: 連携済みプロバイダ一覧が返る")
        void getConnectedProviders_正常_一覧が返る() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));

            // When
            ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(TEST_USER_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getProvider()).isEqualTo("GOOGLE");
            assertThat(response.getData().get(0).getProviderEmail()).isEqualTo(TEST_EMAIL);
        }

        @Test
        @DisplayName("正常系: 連携なしで空リストが返る")
        void getConnectedProviders_連携なし_空リスト() {
            // Given
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When
            ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(TEST_USER_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }

        @Test
        @DisplayName("正常系: 複数プロバイダ連携時に全件返る")
        void getConnectedProviders_複数連携_全件返る() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            OAuthAccountEntity lineAccount = createLineOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID))
                    .willReturn(List.of(googleAccount, lineAccount));

            // When
            ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(TEST_USER_ID);

            // Then
            assertThat(response.getData()).hasSize(2);
        }
    }

    // ========================================
    // disconnectProvider
    // ========================================

    @Nested
    @DisplayName("disconnectProvider")
    class DisconnectProvider {

        @Test
        @DisplayName("正常系: パスワードありユーザーがOAuth連携解除できる")
        void disconnectProvider_パスワードあり_連携解除成功() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));

            // When
            authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE");

            // Then
            verify(oauthAccountRepository).delete(googleAccount);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: 他プロバイダ連携ありでOAuth連携解除できる")
        void disconnectProvider_他プロバイダあり_連携解除成功() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            OAuthAccountEntity lineAccount = createLineOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID))
                    .willReturn(List.of(googleAccount, lineAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createOAuthOnlyUser()));
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When
            authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE");

            // Then
            verify(oauthAccountRepository).delete(googleAccount);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: WebAuthnありでOAuth連携解除できる")
        void disconnectProvider_WebAuthnあり_連携解除成功() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createOAuthOnlyUser()));

            WebAuthnCredentialEntity webauthnCred = WebAuthnCredentialEntity.builder()
                    .userId(TEST_USER_ID)
                    .credentialId("cred-id-1")
                    .publicKey("pk")
                    .signCount(0L)
                    .build();
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(webauthnCred));

            // When
            authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE");

            // Then
            verify(oauthAccountRepository).delete(googleAccount);
        }

        @Test
        @DisplayName("異常系: 未サポートプロバイダでAUTH_028例外")
        void disconnectProvider_未サポートプロバイダ_AUTH028例外() {
            // Given / When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "INVALID"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_028"));
        }

        @Test
        @DisplayName("異常系: 連携なしプロバイダでAUTH_029例外")
        void disconnectProvider_連携なしプロバイダ_AUTH029例外() {
            // Given
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_029"));
        }

        @Test
        @DisplayName("異常系: 最後のログイン手段削除でAUTH_030例外")
        void disconnectProvider_最後のログイン手段_AUTH030例外() {
            // Given（パスワードなし、他プロバイダなし、WebAuthnなし）
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createOAuthOnlyUser()));
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_030"));
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void disconnectProvider_ユーザー不在_AUTH015例外() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }

    // ========================================
    // CMP-261012-0911: OAuth ログイン・連携確認の 2FA ゲート
    // 外部プロバイダ（Google / LINE / Apple）は JDK 標準の HttpServer で偽装し、実 WebClient 経路を通す。
    // ========================================

    @Nested
    @DisplayName("CMP-261012-0911 OAuth 経路の 2FA ゲート")
    class OAuthTwoFactorGate {

        private com.sun.net.httpserver.HttpServer fakeProvider;
        private static final String SUB = "oauth-2fa-sub-1";

        @org.junit.jupiter.api.BeforeEach
        void startFakeProviders() throws java.io.IOException {
            fakeProvider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            String idToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
                    + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                            ("{\"sub\":\"" + SUB + "\",\"email\":\"" + TEST_EMAIL + "\",\"name\":\"yamada\"}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + ".sig";
            fakeProvider.createContext("/google/token", ex -> respond(ex, "{\"access_token\":\"fake-at\"}"));
            fakeProvider.createContext("/google/userinfo", ex -> respond(ex,
                    "{\"sub\":\"" + SUB + "\",\"email\":\"" + TEST_EMAIL + "\","
                            + "\"given_name\":\"太郎\",\"family_name\":\"山田\",\"name\":\"yamada\"}"));
            fakeProvider.createContext("/line/token", ex -> respond(ex,
                    "{\"access_token\":\"fake-at\",\"id_token\":\"" + idToken + "\"}"));
            fakeProvider.createContext("/apple/token", ex -> respond(ex, "{\"id_token\":\"" + idToken + "\"}"));
            fakeProvider.start();
            String base = "http://127.0.0.1:" + fakeProvider.getAddress().getPort();
            lenient().when(oAuthProperties.getGoogleTokenUri()).thenReturn(base + "/google/token");
            lenient().when(oAuthProperties.getGoogleUserinfoUri()).thenReturn(base + "/google/userinfo");
            lenient().when(oAuthProperties.getGoogleRedirectUri()).thenReturn("http://localhost/callback");
            lenient().when(oAuthProperties.getLineTokenUri()).thenReturn(base + "/line/token");
            lenient().when(oAuthProperties.getAppleTokenUri()).thenReturn(base + "/apple/token");
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(authOAuthService, "initWebClient");

            lenient().when(authTokenService.issueAccessToken(any(), any(), anyBoolean())).thenReturn("at");
            lenient().when(authTokenService.generateRefreshToken()).thenReturn("rt");
            lenient().when(authTokenService.hashToken(any())).thenAnswer(inv -> "h-" + inv.getArgument(0));
            lenient().when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @org.junit.jupiter.api.AfterEach
        void stopFakeProviders() {
            fakeProvider.stop(0);
        }

        private void respond(com.sun.net.httpserver.HttpExchange ex, String json) throws java.io.IOException {
            byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        private UserEntity linkedUser(OAuthAccountEntity.OAuthProvider provider) {
            UserEntity user = createOAuthOnlyUser();
            org.springframework.test.util.ReflectionTestUtils.setField(user, "id", TEST_USER_ID);
            OAuthAccountEntity account = OAuthAccountEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(provider)
                    .providerUserId(SUB)
                    .providerEmail(TEST_EMAIL)
                    .build();
            given(oauthAccountRepository.findByProviderAndProviderUserId(provider, SUB)).willReturn(Optional.of(account));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(user));
            lenient().when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            return user;
        }

        private void twoFactorEnabled(com.mannschaft.app.auth.TwoFactorMethod method) {
            lenient().when(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).thenReturn(Optional.of(
                    com.mannschaft.app.auth.entity.TwoFactorAuthEntity.builder()
                            .userId(TEST_USER_ID).method(method).isEnabled(true).backupCodes("[]").build()));
        }

        private com.mannschaft.app.auth.dto.MfaRequiredResponse challenge(com.mannschaft.app.auth.TwoFactorMethod primary) {
            return new com.mannschaft.app.auth.dto.MfaRequiredResponse("mfa-session-token", primary,
                    MfaMethodPolicy.availableMethods(primary, true));
        }

        private List<LoginSuccessEvent> publishedLoginSuccess() {
            org.mockito.ArgumentCaptor<com.mannschaft.app.common.event.DomainEvent> captor =
                    org.mockito.ArgumentCaptor.forClass(com.mannschaft.app.common.event.DomainEvent.class);
            verify(eventPublisher, atLeast(0)).publish(captor.capture());
            return captor.getAllValues().stream()
                    .filter(LoginSuccessEvent.class::isInstance)
                    .map(LoginSuccessEvent.class::cast)
                    .toList();
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(OAuthAccountEntity.OAuthProvider.class)
        @DisplayName("AC-1/AC-16: 2FA 有効ユーザーの既存連携ログインは MFA 要求を返し、トークン・LOGIN_SUCCESS・last_login_at 更新をしない")
        void 既存連携_2FA有効ならMFA要求(OAuthAccountEntity.OAuthProvider provider) {
            UserEntity user = linkedUser(provider);
            twoFactorEnabled(com.mannschaft.app.auth.TwoFactorMethod.TOTP);
            com.mannschaft.app.auth.dto.MfaRequiredResponse expected = challenge(com.mannschaft.app.auth.TwoFactorMethod.TOTP);
            lenient().when(mfaSessionService.issueChallenge(eq(TEST_USER_ID), eq(TEST_EMAIL), eq(com.mannschaft.app.auth.TwoFactorMethod.TOTP),
                    eq("OAUTH_" + provider.name()))).thenReturn(expected);

            ApiResponse<?> res = authOAuthService.loginWithOAuth(
                    provider.name().toLowerCase(), "code", TEST_IP, TEST_USER_AGENT);

            assertThat(res.getData()).as("AC-1 MFA 要求を返す").isSameAs(expected);
            verify(mfaSessionService).issueChallenge(eq(TEST_USER_ID), eq(TEST_EMAIL), eq(com.mannschaft.app.auth.TwoFactorMethod.TOTP),
                    eq("OAUTH_" + provider.name()));
            verify(authTokenService, never()).issueAccessToken(any(), any(), anyBoolean());
            verify(refreshTokenRepository, never()).save(any());
            assertThat(publishedLoginSuccess()).as("AC-1 LOGIN_SUCCESS は出さない").isEmpty();
            assertThat(user.getLastLoginAt()).as("AC-1 last_login_at は更新しない").isNull();
        }

        @Test
        @DisplayName("AC-1: EMAIL 主手段の 2FA ユーザーは主手段 EMAIL でチャレンジを発行する")
        void 既存連携_EMAIL主手段() {
            UserEntity user = linkedUser(OAuthAccountEntity.OAuthProvider.GOOGLE);
            twoFactorEnabled(com.mannschaft.app.auth.TwoFactorMethod.EMAIL);
            com.mannschaft.app.auth.dto.MfaRequiredResponse expected = challenge(com.mannschaft.app.auth.TwoFactorMethod.EMAIL);
            lenient().when(mfaSessionService.issueChallenge(eq(TEST_USER_ID), eq(TEST_EMAIL), eq(com.mannschaft.app.auth.TwoFactorMethod.EMAIL),
                    eq("OAUTH_GOOGLE"))).thenReturn(expected);

            ApiResponse<?> res = authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            assertThat(res.getData()).isSameAs(expected);
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-3: 2FA 無効ユーザーの既存連携ログインは従来どおりトークンを発行し LOGIN_SUCCESS(method=OAUTH_GOOGLE) を出す")
        void 既存連携_2FA無効なら従来どおり() {
            linkedUser(OAuthAccountEntity.OAuthProvider.GOOGLE);
            lenient().when(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).thenReturn(Optional.of(
                    com.mannschaft.app.auth.entity.TwoFactorAuthEntity.builder()
                            .userId(TEST_USER_ID).isEnabled(false).backupCodes("[]").build()));

            ApiResponse<?> res = authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            assertThat(res.getData()).isInstanceOf(TokenResponse.class);
            verify(mfaSessionService, never()).issueChallenge(any(), any(), any(), any());
            assertThat(publishedLoginSuccess()).singleElement()
                    .satisfies(e -> assertThat(e.getMethod()).isEqualTo("OAUTH_GOOGLE"));
        }

        @Test
        @DisplayName("AC-13: 連携競合（未連携・メール一致）は 2FA 有効でも従来どおり OAuthConflictResponse を返し、トークンもチャレンジも出さない")
        void 連携競合は従来どおり() {
            given(oauthAccountRepository.findByProviderAndProviderUserId(any(), any())).willReturn(Optional.empty());
            UserEntity existing = createActiveUser();
            org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", TEST_USER_ID);
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(existing));
            twoFactorEnabled(com.mannschaft.app.auth.TwoFactorMethod.TOTP);

            ApiResponse<?> res = authOAuthService.loginWithOAuth("google", "code", TEST_IP, TEST_USER_AGENT);

            assertThat(res.getData()).isInstanceOf(com.mannschaft.app.auth.dto.OAuthConflictResponse.class);
            verify(mfaSessionService, never()).issueChallenge(any(), any(), any(), any());
            verify(refreshTokenRepository, never()).save(any());
        }

        private OAuthLinkTokenEntity stubLinkToken(String rawToken) {
            OAuthLinkTokenEntity linkToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId(SUB)
                    .providerEmail(TEST_EMAIL)
                    .tokenHash("h-" + rawToken)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(oauthLinkTokenRepository.findByTokenHash("h-" + rawToken)).willReturn(Optional.of(linkToken));
            lenient().when(oauthLinkTokenRepository.save(any(OAuthLinkTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(oauthAccountRepository.save(any(OAuthAccountEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            return linkToken;
        }

        @Test
        @DisplayName("AC-4: 2FA 有効ユーザーの連携確認は連携を作成したうえで MFA 要求を返し、トークンを発行しない")
        void 連携確認_2FA有効ならMFA要求() {
            stubLinkToken("link-raw");
            UserEntity user = createActiveUser();
            org.springframework.test.util.ReflectionTestUtils.setField(user, "id", TEST_USER_ID);
            lenient().when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(user));
            twoFactorEnabled(com.mannschaft.app.auth.TwoFactorMethod.TOTP);
            com.mannschaft.app.auth.dto.MfaRequiredResponse expected = challenge(com.mannschaft.app.auth.TwoFactorMethod.TOTP);
            lenient().when(mfaSessionService.issueChallenge(eq(TEST_USER_ID), eq(TEST_EMAIL), eq(com.mannschaft.app.auth.TwoFactorMethod.TOTP),
                    eq("OAUTH_GOOGLE"))).thenReturn(expected);

            ApiResponse<?> res = linkageConfirm().confirmOAuthLinkage("link-raw", TEST_IP, TEST_USER_AGENT);

            assertThat(res.getData()).as("AC-4 MFA 要求を返す").isSameAs(expected);
            verify(oauthAccountRepository).save(any(OAuthAccountEntity.class));
            verify(refreshTokenRepository, never()).save(any());
            verify(authTokenService, never()).issueAccessToken(any(), any(), anyBoolean());
            assertThat(publishedLoginSuccess()).isEmpty();
        }

        @Test
        @DisplayName("AC-14: 2FA 無効ユーザーの連携確認は即発行し、要求元 ip/UA 付きの LOGIN_SUCCESS(method=OAUTH_GOOGLE) を出す")
        void 連携確認_2FA無効なら即発行とLoginSuccess() {
            stubLinkToken("link-raw-2");
            UserEntity user = createActiveUser();
            org.springframework.test.util.ReflectionTestUtils.setField(user, "id", TEST_USER_ID);
            lenient().when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(user));

            ApiResponse<?> res = linkageConfirm().confirmOAuthLinkage("link-raw-2", TEST_IP, TEST_USER_AGENT);

            assertThat(res.getData()).isInstanceOf(TokenResponse.class);
            List<LoginSuccessEvent> events = publishedLoginSuccess();
            assertThat(events).as("AC-14 LOGIN_SUCCESS を 1 件出す").hasSize(1);
            assertThat(events.get(0).getIpAddress()).isEqualTo(TEST_IP);
            assertThat(events.get(0).getUserAgent()).isEqualTo(TEST_USER_AGENT);
            assertThat(events.get(0).getMethod()).isEqualTo("OAUTH_GOOGLE");
            assertThat(events.get(0).getSessionHash()).isNotNull();
        }
    }
}
