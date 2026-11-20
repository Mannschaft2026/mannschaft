package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.dto.MfaRequiredResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.mfa.MfaValkeyFake;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP-261012-0911 試練: {@link MfaSessionService} の MFA チャレンジ発行と入口方式（loginMethod）の保持。
 *
 * <p>Valkey は外部境界のため {@link MfaValkeyFake}（単一コマンドの原子性を再現するインメモリ代役）で差し替え、
 * 本体は実インスタンスを使う（自前 Bean はモックしない）。</p>
 *
 * <p>対象: AC-1（OAuth 入口の MFA 要求）、AC-5（PASSWORD/OAuth の失敗合算）、AC-7 改（方式キー欠落→PASSWORD・
 * 値は userId のまま＝新旧双方向互換）、AC-12（単回消費・方式キーの同時削除）、判断①（入口方式の保持）。</p>
 */
@DisplayName("CMP-261012-0911 MfaSessionService チャレンジ発行と入口方式")
class MfaSessionServiceTest {

    private static final long USER_ID = 7L;
    private static final String EMAIL = "mfa-session-test@example.com";

    private MfaValkeyFake valkey;
    private MfaSessionService service;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valkey = MfaValkeyFake.install(redis);
        AuthTokenService authTokenService = mock(AuthTokenService.class);
        given(authTokenService.hashToken(anyString())).willAnswer(inv -> "h-" + inv.getArgument(0));
        service = new MfaSessionService(redis, authTokenService, mock(DomainEventPublisher.class),
                Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));
    }

    private static UserEntity user() {
        UserEntity u = UserEntity.builder()
                .email(EMAIL)
                .passwordHash(null)
                .lastName("山田").firstName("太郎").displayName("yamada")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    private static String hashOf(String rawToken) {
        return "h-" + rawToken;
    }

    @Test
    @DisplayName("AC-1/判断①: OAuth 入口のチャレンジはセッションキー=userId・方式キー=OAUTH_GOOGLE を同TTLで保存し、主手段と提示要素を返す")
    void issueChallenge_OAuth入口_セッションと方式キーを保存() {
        MfaRequiredResponse res = service.issueChallenge(user(), TwoFactorMethod.TOTP, "OAUTH_GOOGLE");

        assertThat(res).as("AC-1 MfaRequiredResponse が返る").isNotNull();
        assertThat(res.isMfaRequired()).isTrue();
        assertThat(res.getMfaSessionToken()).isNotBlank();
        assertThat(res.getPrimaryMethod()).isEqualTo(TwoFactorMethod.TOTP);
        assertThat(res.getAvailableMethods())
                .isEqualTo(MfaMethodPolicy.availableMethods(TwoFactorMethod.TOTP, true));

        String hash = hashOf(res.getMfaSessionToken());
        assertThat(valkey.rawGet(MfaSessionService.sessionKey(hash)))
                .as("AC-7 改: セッションキーの値は旧プロセスも読める userId のまま").isEqualTo(String.valueOf(USER_ID));
        assertThat(valkey.rawGet(MfaSessionService.sessionMethodKey(hash)))
                .as("判断①: 入口方式は別キーで保持").isEqualTo("OAUTH_GOOGLE");
        long sessionTtl = valkey.ttlMillis(MfaSessionService.sessionKey(hash));
        long methodTtl = valkey.ttlMillis(MfaSessionService.sessionMethodKey(hash));
        assertThat(sessionTtl).isBetween(MfaSessionService.SESSION_TTL.toMillis() - 2_000, MfaSessionService.SESSION_TTL.toMillis());
        assertThat(methodTtl).as("方式キーはセッションと同 TTL")
                .isBetween(MfaSessionService.SESSION_TTL.toMillis() - 2_000, MfaSessionService.SESSION_TTL.toMillis());
    }

    @Test
    @DisplayName("AC-1: EMAIL 主手段のチャレンジは EMAIL・BACKUP_CODE を提示する")
    void issueChallenge_EMAIL主手段() {
        MfaRequiredResponse res = service.issueChallenge(user(), TwoFactorMethod.EMAIL, "OAUTH_LINE");

        assertThat(res).isNotNull();
        assertThat(res.getPrimaryMethod()).isEqualTo(TwoFactorMethod.EMAIL);
        assertThat(res.getAvailableMethods())
                .isEqualTo(MfaMethodPolicy.availableMethods(TwoFactorMethod.EMAIL, true));
    }

    @Test
    @DisplayName("AC-1/判断①: 発行したチャレンジを resolve すると userId と入口方式が得られる")
    void issueChallenge_resolveで入口方式が得られる() {
        MfaRequiredResponse res = service.issueChallenge(user(), TwoFactorMethod.TOTP, "OAUTH_APPLE");
        assertThat(res).isNotNull();

        MfaSessionService.MfaSession session = service.resolve(res.getMfaSessionToken());

        assertThat(session.userId()).isEqualTo(USER_ID);
        assertThat(session.loginMethod()).isEqualTo("OAUTH_APPLE");
    }

    @Test
    @DisplayName("AC-7 改: 方式キーが無い旧形式セッション（値=userId のみ）は loginMethod=PASSWORD として解決する")
    void resolve_旧形式は方式キー欠落でPASSWORD() {
        String raw = "legacy-token";
        valkey.rawSet(MfaSessionService.sessionKey(hashOf(raw)), String.valueOf(USER_ID), Duration.ofMinutes(5));

        MfaSessionService.MfaSession session = service.resolve(raw);

        assertThat(session.userId()).isEqualTo(USER_ID);
        assertThat(session.loginMethod()).as("AC-7 改: 方式キー欠落→PASSWORD").isEqualTo("PASSWORD");
    }

    @Test
    @DisplayName("AC-7 改: 方式キーがあればその値を loginMethod として解決する")
    void resolve_方式キーがあればその値() {
        String raw = "new-token";
        valkey.rawSet(MfaSessionService.sessionKey(hashOf(raw)), String.valueOf(USER_ID), Duration.ofMinutes(5));
        valkey.rawSet(MfaSessionService.sessionMethodKey(hashOf(raw)), "OAUTH_LINE", Duration.ofMinutes(5));

        MfaSessionService.MfaSession session = service.resolve(raw);

        assertThat(session.loginMethod()).isEqualTo("OAUTH_LINE");
    }

    @Test
    @DisplayName("AC-12: 消費は 1 回だけ成功し、方式キーもセッションと同時に消える")
    void consume_単回消費と方式キー同時削除() {
        MfaRequiredResponse res = service.issueChallenge(user(), TwoFactorMethod.TOTP, "OAUTH_GOOGLE");
        assertThat(res).as("前提: チャレンジ発行").isNotNull();
        MfaSessionService.MfaSession session = service.resolve(res.getMfaSessionToken());

        boolean first = service.consume(session);
        boolean second = service.consume(session);

        assertThat(first).isTrue();
        assertThat(second).as("AC-12 同一 challenge の再消費は不可").isFalse();
        assertThat(valkey.rawGet(MfaSessionService.sessionMethodKey(session.sessionHash())))
                .as("AC-12 方式キーも消える").isNull();
        assertThatThrownBy(() -> service.resolve(res.getMfaSessionToken()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(AuthErrorCode.AUTH_073));
    }

    @Test
    @DisplayName("AC-12: 破棄（試行上限・2FA 消失）でも方式キーが同時に消える")
    void terminate_方式キー同時削除() {
        String raw = "terminate-token";
        valkey.rawSet(MfaSessionService.sessionKey(hashOf(raw)), String.valueOf(USER_ID), Duration.ofMinutes(5));
        valkey.rawSet(MfaSessionService.sessionMethodKey(hashOf(raw)), "OAUTH_GOOGLE", Duration.ofMinutes(5));
        MfaSessionService.MfaSession session = service.resolve(raw);

        service.terminate(session);

        assertThat(valkey.rawGet(MfaSessionService.sessionKey(hashOf(raw)))).isNull();
        assertThat(valkey.rawGet(MfaSessionService.sessionMethodKey(hashOf(raw)))).as("方式キーも消える").isNull();
    }

    @Test
    @DisplayName("AC-5 改: PASSWORD 由来と OAuth 由来のセッションの失敗はユーザー単位で合算され、10 回目でロックされる")
    void recordFailure_入口方式をまたいで合算() {
        MfaSessionService.MfaSession password = new MfaSessionService.MfaSession("h-pw", USER_ID, "PASSWORD");
        MfaSessionService.MfaSession oauth = new MfaSessionService.MfaSession("h-oa", USER_ID, "OAUTH_GOOGLE");

        boolean locked = false;
        for (int i = 1; i <= 5; i++) {
            locked |= service.recordFailure(password, i).lockedNow();
        }
        for (int i = 1; i <= 4; i++) {
            locked |= service.recordFailure(oauth, i).lockedNow();
        }
        assertThat(locked).as("9 回目まではロックしない").isFalse();

        boolean lockedAtTenth = service.recordFailure(oauth, 5).lockedNow();

        assertThat(lockedAtTenth).as("AC-5 改: 入口方式をまたいで 10 回目でロック").isTrue();
        assertThatThrownBy(() -> service.assertNotLocked(USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(AuthErrorCode.AUTH_003));
    }
}
