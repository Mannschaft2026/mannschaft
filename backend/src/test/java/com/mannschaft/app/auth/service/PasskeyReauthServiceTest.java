package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.PasskeyManageReauthMethod;
import com.mannschaft.app.auth.ReauthPurpose;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.event.ReauthenticationFailedEvent;
import com.mannschaft.app.auth.event.ReauthenticationSucceededEvent;
import com.mannschaft.app.auth.event.WebAuthnCredentialRemovedEvent;
import com.mannschaft.app.auth.mfa.MfaValkeyFake;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.event.DomainEvent;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役A「パスキー再認証（CMP-261021-1339 → 1338）」の受け入れ試練（BE 単体）。
 *
 * <p>検証器（WebAuthn4J）はモックせず、{@link PasskeyAssertionFixture} の実 ES256 署名を通す。
 * Valkey は {@link MfaValkeyFake}（単一コマンドの原子性を再現するインメモリ代役）に差し替え、
 * キー名に依存せず「フラグが使えるか」「チャレンジが 1 回限りか」を振る舞いで観測する。
 * 並行性は {@link #raceOnFirstRead} で「読んでから消す」非原子実装の窓を決定的に作る
 * （getAndDelete だけで実装されていれば読み取り自体が起きないため影響しない）。</p>
 *
 * <p>前提にした API 形状（出陣への申し送り）:</p>
 * <ul>
 *   <li>{@code beginReauthenticate(userId, ReauthPurpose)} / {@code completeReauthenticate(userId, sessionHash, req)}</li>
 *   <li>{@code markReauthenticated(userId, sessionHash, purpose)} / {@code consumeReauthentication(userId, sessionHash, purpose)}</li>
 *   <li>{@code beginRegister(userId, sessionHash)} / {@code completeRegister(userId, sessionHash, req)} /
 *       {@code deleteCredential(userId, sessionHash, credentialId)}</li>
 *   <li>再認証が無い・期限切れ・別セッション・別用途は {@code AUTH_087}（新設。4xx＝403 想定）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261021-1339/1338 パスキー再認証 受け入れ試練（AuthWebAuthnService）")
class PasskeyReauthServiceTest {

    private static final String ORIGIN = "https://mannschaft.app";
    private static final String RP_ID = "mannschaft.app";
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long CREDENTIAL_PK = 10L;
    private static final String SESSION_A = "a".repeat(64);
    private static final String SESSION_B = "b".repeat(64);
    /** 再認証が無い・使えないときの新設エラーコード（出陣で AuthErrorCode と GlobalExceptionHandler に追加）。 */
    private static final String REAUTH_REQUIRED = "AUTH_087";
    private static final int UP = 0x01;
    private static final int UV = 0x04;

    @Mock private WebAuthnCredentialRepository webAuthnCredentialRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthService authService;
    @Mock private RoleClaimResolver roleClaimResolver;
    @Mock private Clock clock;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private DomainEventPublisher eventPublisher;

    @InjectMocks private AuthWebAuthnService service;

    private MfaValkeyFake valkey;
    private PasskeyAssertionFixture authenticator;
    private WebAuthnCredentialEntity credential;
    private final Map<String, WebAuthnCredentialEntity> byCredentialId = new ConcurrentHashMap<>();
    private final Map<Long, WebAuthnCredentialEntity> byId = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> afterFirstRead = new AtomicReference<>();

    @BeforeEach
    void 準備() throws Exception {
        ReflectionTestUtils.setField(service, "rpId", RP_ID);
        ReflectionTestUtils.setField(service, "rpOrigin", ORIGIN);
        valkey = MfaValkeyFake.install(redisTemplate);
        hookReads();
        given(clock.instant()).willReturn(Instant.parse("2026-11-25T03:00:00Z"));
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user(USER_ID)));
        given(userRepository.findById(OTHER_USER_ID)).willReturn(Optional.of(user(OTHER_USER_ID)));
        given(webAuthnCredentialRepository.findByCredentialId(anyString()))
                .willAnswer(i -> Optional.ofNullable(byCredentialId.get((String) i.getArgument(0))));
        given(webAuthnCredentialRepository.findById(anyLong()))
                .willAnswer(i -> Optional.ofNullable(byId.get((Long) i.getArgument(0))));
        given(webAuthnCredentialRepository.findByUserId(anyLong())).willAnswer(i -> byCredentialId.values().stream()
                .filter(c -> c.getUserId().equals(i.getArgument(0))).toList());
        given(webAuthnCredentialRepository.save(any(WebAuthnCredentialEntity.class))).willAnswer(i -> {
            WebAuthnCredentialEntity saved = i.getArgument(0);
            byCredentialId.put(saved.getCredentialId(), saved);
            if (saved.getId() != null) {
                byId.put(saved.getId(), saved);
            }
            return saved;
        });
        authenticator = new PasskeyAssertionFixture();
        credential = store(USER_ID, CREDENTIAL_PK, authenticator, 0);
    }

    // ─────────────────────────────────────────────
    // 1339: 再認証の UV 必須化と検証の堅牢化
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("1339 再認証の検証")
    class Reauthenticate {

        @ParameterizedTest
        // 0=UP/UV 無し、1=UP のみ（UV 無し）、4=UV のみ（UP 無し）
        @ValueSource(ints = {0, UP, UV})
        void A1_UVまたはUPの無い再認証assertionは拒否されフラグを書かない(int flags) throws Exception {
            String challenge = begin(ReauthPurpose.PASSKEY_MANAGE);

            assertRejected(() -> complete(SESSION_A, reauth(challenge, flags, 1, 1)), "AUTH_024");

            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isFalse();
            assertThat(persistedSignCount()).isZero();
        }

        @Test
        void A2_UVとUPありの再認証は成功し5分以内ならフラグを使える() throws Exception {
            WebAuthnReauthenticateCompleteResponse response =
                    complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 1, 1));

            assertThat(response.getVerifiedUntil()).isNotNull();
            valkey.advance(Duration.ofSeconds(299));
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isTrue();
        }

        @Test
        void A2_再認証フラグは5分を過ぎると使えない() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 1, 1));

            valkey.advance(Duration.ofSeconds(301));
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isFalse();
        }

        @Test
        void A3_他人のcredentialIdは拒否され本人のチャレンジは焼けない() throws Exception {
            PasskeyAssertionFixture othersAuthenticator = new PasskeyAssertionFixture();
            store(OTHER_USER_ID, 20L, othersAuthenticator, 0);
            String challenge = begin(ReauthPurpose.PASSKEY_MANAGE);

            assertRejected(() -> complete(SESSION_A, othersAuthenticator.reauthAssertion(
                    challenge, ORIGIN, RP_ID, UP | UV, 1, 1)), "AUTH_024");
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isFalse();

            // 照合前に消費していなければ、同じチャレンジで本人の正しい assertion が通る
            complete(SESSION_A, reauth(challenge, UP | UV, 1, 1));
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isTrue();
        }

        @Test
        void A4_同一チャレンジの再送は拒否される() throws Exception {
            WebAuthnReauthenticateCompleteRequest request = reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 1, 1);
            complete(SESSION_A, request);

            assertRejected(() -> complete(SESSION_B, request), "AUTH_027");
            assertThat(service.consumeReauthentication(USER_ID, SESSION_B, ReauthPurpose.PASSKEY_MANAGE)).isFalse();
        }

        @Test
        void A4_同一チャレンジを並行完了しても成功は1回だけ() throws Exception {
            // 0/0 カウンタ（同期パスキー）で送り、signCount 判定に救われずチャレンジの一回性だけを観測する
            WebAuthnReauthenticateCompleteRequest request = reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 0, 0);

            List<Boolean> results = raceOnFirstRead(
                    () -> complete(SESSION_A, request),
                    () -> complete(SESSION_B, request));

            assertThat(results).containsExactlyInAnyOrder(true, false);
        }

        @Test
        void A5_署名済みカウンタが0同士なら成功する() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 0, 0));

            assertThat(persistedSignCount()).isZero();
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isTrue();
        }

        @Test
        void A5_申告signCountを小さく改竄しても検証済みの増加値で成功し保存する() throws Exception {
            setStoredCounter(7);

            complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 8, 0));

            assertThat(persistedSignCount()).isEqualTo(8);
        }

        @ParameterizedTest
        @ValueSource(longs = {6, 7})
        void A5_申告signCountを大きく改竄しても検証済み値が増えていなければAUTH026(long signed) throws Exception {
            setStoredCounter(7);

            assertRejected(() -> complete(SESSION_A,
                    reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, signed, Long.MAX_VALUE)), "AUTH_026");

            assertThat(persistedSignCount()).isEqualTo(7);
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isFalse();
        }

        @Test
        void A7_提示モード用の再認証ではパスキーを削除できない() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.POINTCARD_PRESENT), UP | UV, 1, 1));

            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK), REAUTH_REQUIRED);
            verify(webAuthnCredentialRepository, never()).delete(any());
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.POINTCARD_PRESENT)).isTrue();
        }

        @Test
        void A7_パスキー管理用の再認証では提示モードを開けない() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 1, 1));

            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.POINTCARD_PRESENT)).isFalse();
            service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK);
            verify(webAuthnCredentialRepository).delete(credential);
        }

        @Test
        void B4_パスキー再認証のフラグは再認証したセッションだけで有効() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 1, 1));

            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_B, CREDENTIAL_PK), REAUTH_REQUIRED);
            service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK);
            verify(webAuthnCredentialRepository).delete(credential);
        }

        @Test
        void B7_パスキー再認証の成功を監査イベントで記録する() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.PASSKEY_MANAGE), UP | UV, 1, 1));

            ReauthenticationSucceededEvent event = published(ReauthenticationSucceededEvent.class);
            assertThat(event.getUserId()).isEqualTo(USER_ID);
            assertThat(event.getPurpose()).isEqualTo(ReauthPurpose.PASSKEY_MANAGE);
            assertThat(event.getMethod()).isEqualTo(PasskeyManageReauthMethod.PASSKEY);
        }

        @Test
        void B7_パスキー再認証の失敗を監査イベントで記録する() throws Exception {
            String challenge = begin(ReauthPurpose.POINTCARD_PRESENT);
            assertRejected(() -> complete(SESSION_A, reauth(challenge, UP, 1, 1)), "AUTH_024");

            ReauthenticationFailedEvent event = published(ReauthenticationFailedEvent.class);
            assertThat(event.getUserId()).isEqualTo(USER_ID);
            assertThat(event.getPurpose()).isEqualTo(ReauthPurpose.POINTCARD_PRESENT);
            assertThat(event.getMethod()).isEqualTo(PasskeyManageReauthMethod.PASSKEY);
        }
    }

    // ─────────────────────────────────────────────
    // 1338: 登録・削除に再認証を要求
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("1338 登録・削除の再認証")
    class ManagePasskey {

        @Test
        void B1_再認証なしの登録開始は拒否される() {
            assertRejected(() -> service.beginRegister(USER_ID, SESSION_A), REAUTH_REQUIRED);
            assertThat(valkey.liveKeysMatching("register")).isEmpty();
        }

        @Test
        void B1_再認証なしの登録完了は拒否される() throws Exception {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            String challenge = service.beginRegister(USER_ID, SESSION_A).getData().getChallenge();
            // 登録開始後にフラグが失われた（別操作で消費済み）状態で完了だけ送る
            assertThat(service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE)).isTrue();
            PasskeyAssertionFixture newAuthenticator = new PasskeyAssertionFixture();

            assertRejected(() -> service.completeRegister(USER_ID, SESSION_A,
                    newAuthenticator.attestation(challenge, ORIGIN, RP_ID, UP | UV)), REAUTH_REQUIRED);
            assertThat(byCredentialId).doesNotContainKey(newAuthenticator.credentialId());
        }

        @Test
        void B1_再認証なしのパスキー削除は拒否される() {
            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK), REAUTH_REQUIRED);
            verify(webAuthnCredentialRepository, never()).delete(any());
            verify(eventPublisher, never()).publish(any(WebAuthnCredentialRemovedEvent.class));
        }

        @Test
        void B2_再認証後5分以内はパスキーを削除できる() {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            valkey.advance(Duration.ofSeconds(299));

            service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK);

            verify(webAuthnCredentialRepository).delete(credential);
            verify(eventPublisher).publish(any(WebAuthnCredentialRemovedEvent.class));
        }

        @Test
        void B2_TTL切れの再認証ではパスキーを削除できない() {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            valkey.advance(Duration.ofSeconds(301));

            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK), REAUTH_REQUIRED);
            verify(webAuthnCredentialRepository, never()).delete(any());
        }

        @Test
        void B2_再認証後は登録を開始し完了できる() throws Exception {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            PasskeyAssertionFixture newAuthenticator = new PasskeyAssertionFixture();

            String challenge = service.beginRegister(USER_ID, SESSION_A).getData().getChallenge();
            service.completeRegister(USER_ID, SESSION_A, newAuthenticator.attestation(challenge, ORIGIN, RP_ID, UP | UV));

            assertThat(byCredentialId).containsKey(newAuthenticator.credentialId());
            assertThat(byCredentialId.get(newAuthenticator.credentialId()).getUserId()).isEqualTo(USER_ID);
        }

        @Test
        void B3_フラグは登録完了で消費され2個目の登録には再認証が要る() throws Exception {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            String challenge = service.beginRegister(USER_ID, SESSION_A).getData().getChallenge();
            service.completeRegister(USER_ID, SESSION_A,
                    new PasskeyAssertionFixture().attestation(challenge, ORIGIN, RP_ID, UP | UV));

            assertRejected(() -> service.beginRegister(USER_ID, SESSION_A), REAUTH_REQUIRED);
        }

        @Test
        void B3_フラグは削除で消費され続けての削除には再認証が要る() throws Exception {
            WebAuthnCredentialEntity second = store(USER_ID, 11L, new PasskeyAssertionFixture(), 0);
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK);

            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_A, second.getId()), REAUTH_REQUIRED);
        }

        @Test
        void B3_同時2件の削除で成功は1件だけ() throws Exception {
            WebAuthnCredentialEntity second = store(USER_ID, 11L, new PasskeyAssertionFixture(), 0);
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);

            List<Boolean> results = raceOnFirstRead(
                    () -> { service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK); return null; },
                    () -> { service.deleteCredential(USER_ID, SESSION_A, second.getId()); return null; });

            assertThat(results).containsExactlyInAnyOrder(true, false);
        }

        @Test
        void B3_提示モードのフラグも同時2件の消費で成功は1件だけ() throws Exception {
            complete(SESSION_A, reauth(begin(ReauthPurpose.POINTCARD_PRESENT), UP | UV, 1, 1));

            List<Boolean> results = raceOnFirstRead(
                    () -> service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.POINTCARD_PRESENT)
                            ? true : fail(),
                    () -> service.consumeReauthentication(USER_ID, SESSION_A, ReauthPurpose.POINTCARD_PRESENT)
                            ? true : fail());

            assertThat(results).containsExactlyInAnyOrder(true, false);
        }

        @Test
        void B4_別セッションのフラグでは削除できない() {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);

            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_B, CREDENTIAL_PK), REAUTH_REQUIRED);
            verify(webAuthnCredentialRepository, never()).delete(any());
            service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK);
            verify(webAuthnCredentialRepository).delete(credential);
        }

        @Test
        void B4_他ユーザーのフラグは使えない() {
            service.markReauthenticated(OTHER_USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);

            assertRejected(() -> service.deleteCredential(USER_ID, SESSION_A, CREDENTIAL_PK), REAUTH_REQUIRED);
            assertRejected(() -> service.beginRegister(USER_ID, SESSION_A), REAUTH_REQUIRED);
        }

        @Test
        void B4_セッションを特定できない要求では再認証フラグを使えない() {
            // jti の無い認証（session_hash=null）に対するフラグは立てない／立っても使えない
            catchThrowable(() -> service.markReauthenticated(USER_ID, null, ReauthPurpose.PASSKEY_MANAGE));

            assertRejected(() -> service.deleteCredential(USER_ID, null, CREDENTIAL_PK), REAUTH_REQUIRED);
            assertRejected(() -> service.beginRegister(USER_ID, null), REAUTH_REQUIRED);
        }

        @Test
        void B8_UVなしのattestationは登録を拒否する() throws Exception {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            PasskeyAssertionFixture newAuthenticator = new PasskeyAssertionFixture();
            String challenge = service.beginRegister(USER_ID, SESSION_A).getData().getChallenge();

            assertRejected(() -> service.completeRegister(USER_ID, SESSION_A,
                    newAuthenticator.attestation(challenge, ORIGIN, RP_ID, UP)), "AUTH_024");
            assertThat(byCredentialId).doesNotContainKey(newAuthenticator.credentialId());
        }

        @Test
        void B8_登録チャレンジは1回限りで再送は拒否される() throws Exception {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            String challenge = service.beginRegister(USER_ID, SESSION_A).getData().getChallenge();
            WebAuthnRegisterCompleteRequest request =
                    new PasskeyAssertionFixture().attestation(challenge, ORIGIN, RP_ID, UP | UV);
            service.completeRegister(USER_ID, SESSION_A, request);
            byCredentialId.remove(request.getCredentialId());
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);

            assertRejected(() -> service.completeRegister(USER_ID, SESSION_A, request), "AUTH_027");
            assertThat(byCredentialId).doesNotContainKey(request.getCredentialId());
        }

        @Test
        void B8_登録チャレンジを並行完了しても成功は1回だけ() throws Exception {
            service.markReauthenticated(USER_ID, SESSION_A, ReauthPurpose.PASSKEY_MANAGE);
            service.markReauthenticated(USER_ID, SESSION_B, ReauthPurpose.PASSKEY_MANAGE);
            String challenge = service.beginRegister(USER_ID, SESSION_A).getData().getChallenge();
            WebAuthnRegisterCompleteRequest request =
                    new PasskeyAssertionFixture().attestation(challenge, ORIGIN, RP_ID, UP | UV);
            // 重複 credential 検査（DB の一意制約）に救われず、チャレンジの一回性だけを観測する
            given(webAuthnCredentialRepository.findByCredentialId(request.getCredentialId()))
                    .willReturn(Optional.empty());

            List<Boolean> results = raceOnFirstRead(
                    () -> service.completeRegister(USER_ID, SESSION_A, request),
                    () -> service.completeRegister(USER_ID, SESSION_B, request));

            assertThat(results).containsExactlyInAnyOrder(true, false);
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private static UserEntity user(Long id) {
        UserEntity user = UserEntity.builder()
                .email("reauth-" + id + "@example.com")
                .passwordHash("$2a$12$encodedPasswordHash")
                .lastName("試練").firstName("太郎").displayName("再認証試練")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private WebAuthnCredentialEntity store(Long ownerId, Long pk, PasskeyAssertionFixture fixture, long count) {
        WebAuthnCredentialEntity entity = WebAuthnCredentialEntity.builder()
                .id(pk).userId(ownerId)
                .credentialId(fixture.credentialId()).publicKey(fixture.publicKey())
                .signCount(count).deviceName("試験用認証器")
                .aaguid("00000000-0000-0000-0000-000000000000")
                .build();
        byCredentialId.put(entity.getCredentialId(), entity);
        byId.put(pk, entity);
        return entity;
    }

    private void setStoredCounter(long count) {
        credential = store(USER_ID, CREDENTIAL_PK, authenticator, count);
    }

    /** 検証後に永続化される（save で置き換え／エンティティ更新のどちらでも観測できる）カウンタ。 */
    private long persistedSignCount() {
        return byCredentialId.get(authenticator.credentialId()).getSignCount();
    }

    private String begin(ReauthPurpose purpose) {
        return service.beginReauthenticate(USER_ID, purpose).getData().getChallenge();
    }

    private WebAuthnReauthenticateCompleteResponse complete(String sessionHash, WebAuthnReauthenticateCompleteRequest req) {
        return service.completeReauthenticate(USER_ID, sessionHash, req).getData();
    }

    private WebAuthnReauthenticateCompleteRequest reauth(String challenge, int flags, long signed, long reported)
            throws Exception {
        return authenticator.reauthAssertion(challenge, ORIGIN, RP_ID, flags, signed, reported);
    }

    private static void assertRejected(ThrowingCallable call, String expectedCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(expectedCode));
    }

    private <T> T published(Class<T> type) {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " が発行されていない: "
                        + captor.getAllValues()));
    }

    private static Boolean fail() {
        throw new IllegalStateException("フラグを消費できなかった");
    }

    /**
     * Valkey の読み取り（{@code get} / {@code hasKey}）の直後に 1 回だけ割り込み処理を差し込めるよう、
     * 代役の上にもう 1 枚かぶせる。「読んでから消す」非原子実装の窓を決定的に作るため
     * （{@code getAndDelete} だけで実装されていれば読み取り自体が起きず、割り込みは発火しない）。
     */
    @SuppressWarnings("unchecked")
    private void hookReads() {
        ValueOperations<String, String> fakeOps = redisTemplate.opsForValue();
        ValueOperations<String, String> hooked = (ValueOperations<String, String>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(fakeOps, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("get") && args != null && args.length == 1) {
                        fireAfterRead();
                    }
                    return result;
                });
        given(redisTemplate.opsForValue()).willReturn(hooked);
        given(redisTemplate.hasKey(anyString())).willAnswer(i -> {
            boolean present = fakeOps.get((String) i.getArgument(0)) != null;
            fireAfterRead();
            return present;
        });
    }

    private void fireAfterRead() {
        Runnable action = afterFirstRead.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }

    /**
     * 同じ資源を奪い合う 2 要求を決定的に競合させ、それぞれが成功したか（true）を返す。
     * 1 つ目の要求が Valkey を最初に「読んだ直後」に 2 つ目の要求を最後まで走らせ、その後 1 つ目を続ける。
     * 読み取りが起きなければ（原子実装）、1 つ目の完了後に 2 つ目を順に走らせる。
     * {@link BusinessException} 以外の例外（試練スタブの UnsupportedOperationException 等）はそのまま失敗にする。
     */
    private List<Boolean> raceOnFirstRead(Callable<?> first, Callable<?> second) {
        AtomicBoolean secondRan = new AtomicBoolean();
        AtomicBoolean secondSucceeded = new AtomicBoolean();
        afterFirstRead.set(() -> {
            secondRan.set(true);
            secondSucceeded.set(succeeded(second));
        });
        boolean firstSucceeded = succeeded(first);
        afterFirstRead.set(null);
        if (!secondRan.get()) {
            secondSucceeded.set(succeeded(second));
        }
        return List.of(firstSucceeded, secondSucceeded.get());
    }

    private static boolean succeeded(Callable<?> task) {
        try {
            task.call();
            return true;
        } catch (BusinessException | IllegalStateException e) {
            return false;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
