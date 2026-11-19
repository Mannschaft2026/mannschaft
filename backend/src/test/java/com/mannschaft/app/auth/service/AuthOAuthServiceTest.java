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

    @InjectMocks
    private AuthOAuthService authOAuthService;

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
            ApiResponse<TokenResponse> response = authOAuthService.confirmOAuthLinkage(rawToken);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("jwt-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("raw-refresh-token");
            verify(oauthAccountRepository).save(any(OAuthAccountEntity.class));
            verify(eventPublisher).publish(any());
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
            assertThatThrownBy(() -> authOAuthService.confirmOAuthLinkage(rawToken))
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
            assertThatThrownBy(() -> authOAuthService.confirmOAuthLinkage(rawToken))
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
            assertThatThrownBy(() -> authOAuthService.confirmOAuthLinkage(rawToken))
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
}
