package com.mannschaft.app.auth;

import com.mannschaft.app.admin.service.BetaRestrictionService;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthRegistrationService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.postal.CountryResolver;
import com.mannschaft.app.postal.PostalCodePolicyRegistry;
import com.mannschaft.app.auth.service.PendingInviteStore;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.dto.RegistrationInviteInfo;
import com.mannschaft.app.role.service.InviteAcceptanceFacade;
import com.mannschaft.app.role.service.InviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthRegistrationService} の単体テスト。
 * AuthService から分離されたユーザー登録 / メール認証ロジックを検証する。
 *
 * <p>セキュリティ重要案件として、AuthServiceTest から該当 nested クラス単位で
 * テスト挙動を完全保存したまま機械的に移送した。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRegistrationService 単体テスト")
class AuthRegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private BetaRestrictionService betaRestrictionService;

    @Mock
    private InviteAcceptanceFacade inviteAcceptanceFacade;

    // CMP-261107-1833: 登録時は招待を読むだけ（role の InviteService）で、保留行を auth の Store に作る
    @Mock
    private InviteService inviteService;

    @Mock
    private PendingInviteStore pendingInviteStore;

    @Spy
    private SecretTokenVault secretTokenVault = new SecretTokenVault();

    // F02.10 §391 郵便番号検証基盤: 実ロジック（JP 固定）を使う
    @Spy
    private CountryResolver countryResolver = new CountryResolver();

    @Spy
    private PostalCodePolicyRegistry postalCodePolicyRegistry = new PostalCodePolicyRegistry();

    @InjectMocks
    private AuthRegistrationService authRegistrationService;

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Password1!";
    private static final String TEST_IP = "127.0.0.1";
    private static final String ENCODED_PASSWORD = "$2a$12$encodedPasswordHash";

    private UserEntity createActiveUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private UserEntity createPendingUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                .isSearchable(true)
                .build();
    }

    private RegisterRequest createRegisterRequest() {
        // F02.10 §391: locale=ja は対応国 JP に解決され郵便番号必須のため、有効な郵便番号を与える
        return new RegisterRequest(
                TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", "123-4567", "ja", "Asia/Tokyo", null, "2000-01-01",
                true, "1.1.0");
    }

    // ========================================
    // register
    // ========================================

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("正常系: ユーザーが作成される")
        void register_正常_ユーザーが作成される() {
            // Given
            RegisterRequest req = createRegisterRequest();
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(userRepository).save(any(UserEntity.class));
            verify(emailVerificationTokenRepository).save(any(EmailVerificationTokenEntity.class));
            // F02.10: UserRegisteredEvent + UserPostalCodeUpdatedEvent の 2 イベントが発行される
            verify(eventPublisher, times(2)).publish(any());
        }

        // AC-4: JP・郵便番号フォーマット不正（"111"）→ AUTH_072
        @Test
        @DisplayName("AC-4 異常系: JP・郵便番号フォーマット不正でAUTH_072例外")
        void register_郵便番号フォーマット不正_AUTH072例外() {
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", "111", "ja", "Asia/Tokyo", null, "2000-01-01",
                    true, "1.1.0");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_072"));
            verify(userRepository, never()).save(any());
        }

        // AC-5: JP・郵便番号未入力（空）→ AUTH_071（対応国では必須）
        @Test
        @DisplayName("AC-5 異常系: JP・郵便番号未入力でAUTH_071例外")
        void register_郵便番号未入力_AUTH071例外() {
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01",
                    true, "1.1.0");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_071"));
            verify(userRepository, never()).save(any());
        }

        // AC-6: 未対応国（locale=fr）は郵便番号検証スキップ → 登録成功
        @Test
        @DisplayName("AC-6 正常系: 未対応国（fr）は郵便番号検証スキップで登録成功")
        void register_未対応国_郵便番号検証スキップ_成功() {
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, "fr", "Europe/Paris", null, "2000-01-01",
                    true, "1.1.0");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            ApiResponse<MessageResponse> response = authRegistrationService.register(req, TEST_IP);

            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("異常系: メール重複でAUTH_004例外")
        void register_メール重複_AUTH004例外() {
            // Given
            RegisterRequest req = createRegisterRequest();
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_004"));
        }

        @Test
        @DisplayName("異常系: パスワードポリシー違反でAUTH_008例外")
        void register_パスワードポリシー違反_AUTH008例外() {
            // Given: 短すぎるパスワード
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, "short", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01",
                    true, "1.1.0");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }

        @Test
        @DisplayName("異常系: 文字種不足のパスワードでAUTH_008例外")
        void register_文字種不足_AUTH008例外() {
            // Given: 8文字以上だが小文字と数字の2種のみ
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, "password123", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01",
                    true, "1.1.0");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }

        @Test
        @DisplayName("正常系: locale/timezone省略時にデフォルト値が使用される")
        void register_locale省略_デフォルト値() {
            // Given: locale 省略は既定 "ja"（=JP）に解決されるため有効な郵便番号を与える（F02.10 §391）
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", "123-4567", null, null, null, "2000-01-01",
                    true, "1.1.0");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> {
                UserEntity saved = invocation.getArgument(0);
                assertThat(saved.getLocale()).isEqualTo("ja");
                assertThat(saved.getTimezone()).isEqualTo("Asia/Tokyo");
                return saved;
            });
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
        }

        @Test
        @DisplayName("異常系: ベータ制限ON・トークンなし・AUTH_042例外")
        void register_ベータ制限ON_トークンなし_AUTH042() {
            // Given
            given(betaRestrictionService.isEnabled()).willReturn(true);
            RegisterRequest req = new RegisterRequest(
                    "new@example.com", "Password1!", "山田", "太郎", "yamada",
                    "123-4567", "ja", "Asia/Tokyo", null, "2000-01-01",
                    true, "1.1.0");

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_042"));
        }

        @Test
        @DisplayName("異常系: ベータ制限ON・トークン無効・AUTH_043例外")
        void register_ベータ制限ON_トークン無効_AUTH043() {
            // Given
            given(betaRestrictionService.isEnabled()).willReturn(true);
            given(betaRestrictionService.isBetaTokenValid("bad-token")).willReturn(false);
            RegisterRequest req = new RegisterRequest(
                    "new@example.com", "Password1!", "山田", "太郎", "yamada",
                    "123-4567", "ja", "Asia/Tokyo", "bad-token", "2000-01-01",
                    true, "1.1.0");

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_043"));
        }

        @Test
        @DisplayName("AC5/AC11 正常系: ベータ制限ON・トークン有効・登録成功（Facade は呼ばず保留を作る。CMP-261107-1833 で反転）")
        void register_ベータ制限ON_トークン有効_登録成功() {
            // Given
            given(betaRestrictionService.isEnabled()).willReturn(true);
            given(betaRestrictionService.isBetaTokenValid("valid-token")).willReturn(true);
            given(inviteService.findRegistrationInvite("valid-token"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(10L, null, false, true)));
            given(userRepository.existsByEmail(any())).willReturn(false);
            given(passwordEncoder.encode(any())).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> withId(inv.getArgument(0), 501L));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            RegisterRequest req = new RegisterRequest(
                    "new@example.com", "Password1!", "山田", "太郎", "yamada",
                    "123-4567", "ja", "Asia/Tokyo", "valid-token", "2000-01-01",
                    true, "1.1.0");

            // When
            authRegistrationService.register(req, TEST_IP);

            // Then
            verify(pendingInviteStore).createPending(501L, new SecretTokenVault().hash("valid-token"), "TEAM", 10L);
            verifyNoInteractions(inviteAcceptanceFacade);
        }
    }

    // ========================================
    // register: 招待付き新規登録の保留承認（CMP-261107-1833）
    // ========================================

    private static UserEntity withId(UserEntity user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static RegisterRequest inviteRequest(String inviteToken) {
        return new RegisterRequest(
                "invitee@example.com", "Password1!", "山田", "太郎", "yamada",
                "123-4567", "ja", "Asia/Tokyo", inviteToken, "2000-01-01",
                true, "1.1.0");
    }

    private void givenUserCanBeSaved(Long userId) {
        given(userRepository.existsByEmail(any())).willReturn(false);
        given(passwordEncoder.encode(any())).willReturn(ENCODED_PASSWORD);
        given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> withId(inv.getArgument(0), userId));
        given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
        given(emailVerificationTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private static void assertErrorCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo(code);
    }

    @Nested
    @DisplayName("register 招待付き（CMP-261107-1833 保留承認方式）")
    class RegisterWithInvite {

        @Test
        @DisplayName("AC4: 宛先付き招待はベータ有効でも AUTH_083 で拒否し、users を作らない")
        void 宛先付き招待_ベータ有効_AUTH083() {
            lenient().when(betaRestrictionService.isEnabled()).thenReturn(true);
            lenient().when(betaRestrictionService.isBetaTokenValid("targeted-invite")).thenReturn(true);
            given(inviteService.findRegistrationInvite("targeted-invite"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(10L, null, true, true)));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authRegistrationService.register(inviteRequest("targeted-invite"), TEST_IP));

            assertErrorCode(thrown, "AUTH_083");
            verify(userRepository, never()).save(any());
            verifyNoInteractions(pendingInviteStore, inviteAcceptanceFacade, eventPublisher);
        }

        @Test
        @DisplayName("AC4: 宛先付き招待はベータ無効でも AUTH_083 で拒否し、users を作らない")
        void 宛先付き招待_ベータ無効_AUTH083() {
            lenient().when(betaRestrictionService.isEnabled()).thenReturn(false);
            given(inviteService.findRegistrationInvite("targeted-invite"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(null, 20L, true, true)));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authRegistrationService.register(inviteRequest("targeted-invite"), TEST_IP));

            assertErrorCode(thrown, "AUTH_083");
            verify(userRepository, never()).save(any());
            verifyNoInteractions(pendingInviteStore, inviteAcceptanceFacade, eventPublisher);
        }

        @Test
        @DisplayName("AC4: 宛先付き招待は失効済みでも AUTH_083（期限切れの宛先付きで登録を通さない）")
        void 宛先付き招待_失効済み_AUTH083() {
            lenient().when(betaRestrictionService.isEnabled()).thenReturn(false);
            given(inviteService.findRegistrationInvite("targeted-invite"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(10L, null, true, false)));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authRegistrationService.register(inviteRequest("targeted-invite"), TEST_IP));

            assertErrorCode(thrown, "AUTH_083");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC5: ベータ有効・有効な共有招待（組織）→ users 保存の後に同TXで保留を作り、ハッシュのみ渡す")
        void ベータ有効_有効招待_保留作成() {
            given(betaRestrictionService.isEnabled()).willReturn(true);
            given(betaRestrictionService.isBetaTokenValid("shared-invite")).willReturn(true);
            given(inviteService.findRegistrationInvite("shared-invite"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(null, 20L, false, true)));
            givenUserCanBeSaved(502L);

            authRegistrationService.register(inviteRequest("shared-invite"), TEST_IP);

            InOrder order = inOrder(userRepository, pendingInviteStore);
            order.verify(userRepository).save(any(UserEntity.class));
            order.verify(pendingInviteStore).createPending(502L, new SecretTokenVault().hash("shared-invite"),
                    "ORGANIZATION", 20L);
            verify(pendingInviteStore, never()).createPending(any(), eq("shared-invite"), any(), any());
            verifyNoInteractions(inviteAcceptanceFacade);
            verify(eventPublisher, times(2)).publish(any());
        }

        @Test
        @DisplayName("AC5: ベータ有効・招待なしは AUTH_042（保留も users も作らない）")
        void ベータ有効_招待なし_AUTH042() {
            given(betaRestrictionService.isEnabled()).willReturn(true);

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authRegistrationService.register(inviteRequest(null), TEST_IP));

            assertErrorCode(thrown, "AUTH_042");
            verify(userRepository, never()).save(any());
            verifyNoInteractions(pendingInviteStore, inviteAcceptanceFacade);
        }

        @Test
        @DisplayName("AC5: ベータ有効・不正/ID制限超え（isBetaTokenValid=false）は AUTH_043（保留も users も作らない）")
        void ベータ有効_不正招待_AUTH043() {
            given(betaRestrictionService.isEnabled()).willReturn(true);
            given(betaRestrictionService.isBetaTokenValid("shared-invite")).willReturn(false);
            lenient().when(inviteService.findRegistrationInvite("shared-invite"))
                    .thenReturn(Optional.of(new RegistrationInviteInfo(10L, null, false, true)));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> authRegistrationService.register(inviteRequest("shared-invite"), TEST_IP));

            assertErrorCode(thrown, "AUTH_043");
            verify(userRepository, never()).save(any());
            verifyNoInteractions(pendingInviteStore, inviteAcceptanceFacade);
        }

        @Test
        @DisplayName("AC5: ベータ無効・存在しない招待は保留を作らず登録だけ成功する")
        void ベータ無効_不在招待_保留なしで成功() {
            given(betaRestrictionService.isEnabled()).willReturn(false);
            given(inviteService.findRegistrationInvite("unknown-invite")).willReturn(Optional.empty());
            givenUserCanBeSaved(503L);

            ApiResponse<MessageResponse> response = authRegistrationService.register(inviteRequest("unknown-invite"), TEST_IP);

            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(userRepository).save(any(UserEntity.class));
            verifyNoInteractions(pendingInviteStore, inviteAcceptanceFacade);
        }

        @Test
        @DisplayName("AC5/AC6: ベータ無効・存在するが失効済みの共有招待は保留を作る（ログイン後に失効案内）")
        void ベータ無効_失効招待_保留作成() {
            given(betaRestrictionService.isEnabled()).willReturn(false);
            given(inviteService.findRegistrationInvite("expired-invite"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(10L, null, false, false)));
            givenUserCanBeSaved(504L);

            authRegistrationService.register(inviteRequest("expired-invite"), TEST_IP);

            verify(pendingInviteStore).createPending(504L, new SecretTokenVault().hash("expired-invite"), "TEAM", 10L);
            verifyNoInteractions(inviteAcceptanceFacade);
        }

        @Test
        @DisplayName("AC1/AC11: 招待なし登録は保留も Facade も触らない")
        void 招待なし_保留なし() {
            given(betaRestrictionService.isEnabled()).willReturn(false);
            givenUserCanBeSaved(505L);

            authRegistrationService.register(inviteRequest(null), TEST_IP);

            verifyNoInteractions(pendingInviteStore, inviteAcceptanceFacade, inviteService);
        }

        @Test
        @DisplayName("AC1: 保留の作成に失敗したら登録ごと失敗する（users と同TX・握りつぶさない）")
        void 保留作成失敗_登録失敗() {
            given(betaRestrictionService.isEnabled()).willReturn(false);
            given(inviteService.findRegistrationInvite("shared-invite"))
                    .willReturn(Optional.of(new RegistrationInviteInfo(10L, null, false, true)));
            givenUserCanBeSaved(506L);
            org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("試練"))
                    .when(pendingInviteStore).createPending(any(), any(), any(), any());

            assertThatThrownBy(() -> authRegistrationService.register(inviteRequest("shared-invite"), TEST_IP))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    // ========================================
    // verifyEmail
    // ========================================

    @Nested
    @DisplayName("verifyEmail")
    class VerifyEmail {

        @Test
        @DisplayName("正常系: ユーザーがACTIVEになる")
        void verifyEmail_正常_ユーザーがACTIVE() {
            // Given
            String rawToken = "valid-token";
            String tokenHash = "hashed-valid-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(verificationToken));

            UserEntity pendingUser = createPendingUser();
            given(userRepository.findById(1L)).willReturn(Optional.of(pendingUser));

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.verifyEmail(rawToken);

            // Then
            assertThat(response.getData().getMessage()).contains("メール認証が完了");
            assertThat(pendingUser.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン期限切れでAUTH_005例外")
        void verifyEmail_トークン期限切れ_AUTH005例外() {
            // Given
            String rawToken = "expired-token";
            String tokenHash = "hashed-expired-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity expiredToken = EmailVerificationTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().minusHours(1)) // 期限切れ
                    .build();
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_005例外")
        void verifyEmail_トークン不在_AUTH005例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(emailVerificationTokenRepository.findByTokenHash("hashed-nonexistent"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_005例外")
        void verifyEmail_使用済みトークン_AUTH005例外() {
            // Given
            String rawToken = "used-token";
            String tokenHash = "hashed-used-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity usedToken = EmailVerificationTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            usedToken.markUsed(); // 使用済みにする
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_005例外")
        void verifyEmail_ユーザー不在_AUTH005例外() {
            // Given
            String rawToken = "valid-token";
            String tokenHash = "hashed-valid-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                    .userId(999L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(verificationToken));
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }
    }

    // ========================================
    // resendVerificationEmail
    // ========================================

    @Nested
    @DisplayName("resendVerificationEmail")
    class ResendVerificationEmail {

        @Test
        @DisplayName("正常系: PENDING_VERIFICATIONユーザーに再送される")
        void resendVerificationEmail_正常_再送() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            UserEntity pendingUser = createPendingUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(pendingUser));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.resendVerificationEmail(TEST_EMAIL, "127.0.0.1");

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailVerificationTokenRepository).save(any(EmailVerificationTokenEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: ユーザー不在でも同一レスポンス（情報漏洩防止）")
        void resendVerificationEmail_ユーザー不在_同一レスポンス() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.resendVerificationEmail(TEST_EMAIL, "127.0.0.1");

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailVerificationTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: ACTIVE状態のユーザーには再送しない（情報漏洩防止）")
        void resendVerificationEmail_ACTIVEユーザー_再送しない() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(createActiveUser()));

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.resendVerificationEmail(TEST_EMAIL, "127.0.0.1");

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailVerificationTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: クールダウン期間中にAUTH_006例外")
        void resendVerificationEmail_クールダウン中_AUTH006例外() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.resendVerificationEmail(TEST_EMAIL, "127.0.0.1"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_006"));
        }
    }
}
