package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.mfa.MfaItSupport;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 戦役A「パスキー再認証（CMP-261021-1339 → 1338）」の受け入れ試練（実 Controller / Security / MySQL）。
 *
 * <p>実 JWT（Bearer）で認証し、実署名（{@link PasskeyAssertionFixture}）を HTTP で送る。検証器はモックしない。
 * Valkey は {@link MfaItSupport} の代役（GETDEL 等の単一コマンド原子性を再現）を使う。
 * Docker の無い環境では {@code @EnabledIf} でスキップされる（red/green の実測は CI）。</p>
 *
 * <p>前提にした HTTP 契約（出陣への申し送り）:</p>
 * <ul>
 *   <li>{@code POST /reauthenticate-begin} 本文 {@code {"purpose":"PASSKEY_MANAGE"|"POINTCARD_PRESENT"}}</li>
 *   <li>{@code POST /reauthenticate-factor} 本文 {@code {"currentPassword","method","code"}}（パスワード／2FA／メールOTP 経路）</li>
 *   <li>{@code GET /reauthenticate-method} → {@code data.method}（PASSKEY / PASSWORD / PASSWORD_AND_SECOND_FACTOR / EMAIL_OTP）</li>
 *   <li>再認証が無い・期限切れ・別セッション・別用途の登録／削除は 4xx（{@code AUTH_087}）</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-1339/1338 パスキー再認証 契約試練（IT）")
class PasskeyReauthContractIT extends MfaItSupport {

    private static final String BASE = "/api/v1/auth/webauthn";
    private static final String REAUTH_BEGIN = BASE + "/reauthenticate-begin";
    private static final String REAUTH_COMPLETE = BASE + "/reauthenticate-complete";
    private static final String REAUTH_FACTOR = BASE + "/reauthenticate-factor";
    private static final String REAUTH_METHOD = BASE + "/reauthenticate-method";
    private static final String REGISTER_BEGIN = BASE + "/register/begin";
    private static final String REGISTER_COMPLETE = BASE + "/register/complete";
    private static final int UP = 0x01;
    private static final int UV = 0x04;

    @Autowired private WebAuthnCredentialRepository credentials;
    @Autowired private AuthTokenService authTokenService;
    @Value("${mannschaft.webauthn.rp-id:mannschaft.app}") private String rpId;
    @Value("${mannschaft.webauthn.origin:https://mannschaft.app}") private String origin;

    private long userId;
    private PasskeyAssertionFixture authenticator;
    private WebAuthnCredentialEntity credential;
    private String sessionA;

    @BeforeEach
    void 準備() throws Exception {
        userId = createPlainUser();
        authenticator = new PasskeyAssertionFixture();
        credential = saveCredential(userId, authenticator);
        sessionA = authTokenService.issueAccessToken(userId, List.of());
    }

    @AfterEach
    void 後始末() {
        credentials.deleteAll(credentials.findByUserId(userId));
    }

    // ─── 1339 ───

    @ParameterizedTest
    @ValueSource(ints = {0, UP, UV})
    void A1_UVまたはUPの無い実署名の再認証は拒否されフラグを書かない(int flags) throws Exception {
        String challenge = reauthBegin(sessionA, "PASSKEY_MANAGE");

        assert4xx(reauthComplete(sessionA, reauth(challenge, flags, 1)));
        assert4xx(deleteCredential(sessionA, credential.getId()));
        assertThat(credentials.findById(credential.getId())).isPresent();
    }

    @Test
    void A2_UVとUPありの再認証は成功しフラグで削除できる() throws Exception {
        MvcResult completed = reauthComplete(sessionA, reauth(reauthBegin(sessionA, "PASSKEY_MANAGE"), UP | UV, 1));
        assertThat(completed.getResponse().getStatus()).as(body(completed)).isEqualTo(200);
        assertThat(json(completed).path("data").path("verifiedUntil").asText()).isNotBlank();

        MvcResult deleted = deleteCredential(sessionA, credential.getId());
        assertThat(deleted.getResponse().getStatus()).as(body(deleted)).isEqualTo(204);
        assertThat(credentials.findById(credential.getId())).isEmpty();
    }

    @Test
    void A5_申告signCountを改竄しても検証済み値で判定する() throws Exception {
        WebAuthnReauthenticateCompleteRequest tampered = authenticator.reauthAssertion(
                reauthBegin(sessionA, "PASSKEY_MANAGE"), origin, rpId, UP | UV, 0, 0);

        MvcResult completed = reauthComplete(sessionA, tampered);
        assertThat(completed.getResponse().getStatus()).as("0/0 は成功: %s", body(completed)).isEqualTo(200);
    }

    @Test
    void A7_提示モード用の再認証ではパスキーを削除できない() throws Exception {
        MvcResult completed = reauthComplete(sessionA, reauth(reauthBegin(sessionA, "POINTCARD_PRESENT"), UP | UV, 1));
        assertThat(completed.getResponse().getStatus()).as(body(completed)).isEqualTo(200);

        assert4xx(deleteCredential(sessionA, credential.getId()));
        assertThat(credentials.findById(credential.getId())).isPresent();
    }

    // ─── 1338 ───

    @Test
    void B1_再認証なしの登録開始と登録完了と削除は4xx() throws Exception {
        assert4xx(perform(post(REGISTER_BEGIN), sessionA, null));
        WebAuthnRegisterCompleteRequest attestation = new PasskeyAssertionFixture().attestation(
                UUID.randomUUID().toString(), origin, rpId, UP | UV);
        assert4xx(perform(post(REGISTER_COMPLETE), sessionA, attestation));
        assert4xx(deleteCredential(sessionA, credential.getId()));
        assertThat(credentials.findByUserId(userId)).hasSize(1);
    }

    @Test
    void B4_別セッションの再認証では削除できない() throws Exception {
        String sessionB = authTokenService.issueAccessToken(userId, List.of());
        reauthOk(sessionA, "PASSKEY_MANAGE");

        assert4xx(deleteCredential(sessionB, credential.getId()));
        MvcResult deleted = deleteCredential(sessionA, credential.getId());
        assertThat(deleted.getResponse().getStatus()).as(body(deleted)).isEqualTo(204);
    }

    @Test
    void B4_他ユーザーの再認証では削除できない() throws Exception {
        long otherId = createPlainUser();
        PasskeyAssertionFixture otherAuthenticator = new PasskeyAssertionFixture();
        saveCredential(otherId, otherAuthenticator);
        String otherSession = authTokenService.issueAccessToken(otherId, List.of());
        String challenge = reauthBegin(otherSession, "PASSKEY_MANAGE");
        MvcResult completed = reauthComplete(otherSession,
                otherAuthenticator.reauthAssertion(challenge, origin, rpId, UP | UV, 1, 1));
        assertThat(completed.getResponse().getStatus()).as(body(completed)).isEqualTo(200);

        // 他人は自分のパスキーを消せない（所有者確認）。本人のセッションにもフラグは無い
        assert4xx(deleteCredential(otherSession, credential.getId()));
        assert4xx(deleteCredential(sessionA, credential.getId()));
        assertThat(credentials.findById(credential.getId())).isPresent();
        credentials.deleteAll(credentials.findByUserId(otherId));
    }

    @Test
    void B3_B8_再認証後の登録は1回限りでUVなしattestationは拒否される() throws Exception {
        credentials.deleteAll(credentials.findByUserId(userId));
        reauthFactorOk(sessionA, Map.of("currentPassword", PASSWORD));

        String challenge = data(perform(post(REGISTER_BEGIN), sessionA, null)).path("challenge").asText();
        PasskeyAssertionFixture noUv = new PasskeyAssertionFixture();
        assert4xx(perform(post(REGISTER_COMPLETE), sessionA, noUv.attestation(challenge, origin, rpId, UP)));
        assertThat(credentials.findByUserId(userId)).isEmpty();

        reauthFactorOk(sessionA, Map.of("currentPassword", PASSWORD));
        String second = data(perform(post(REGISTER_BEGIN), sessionA, null)).path("challenge").asText();
        MvcResult registered = perform(post(REGISTER_COMPLETE), sessionA,
                new PasskeyAssertionFixture().attestation(second, origin, rpId, UP | UV));
        assertThat(registered.getResponse().getStatus()).as(body(registered)).isEqualTo(201);
        assertThat(credentials.findByUserId(userId)).hasSize(1);

        // フラグは消費済み: 2 個目の登録には再認証が要る
        assert4xx(perform(post(REGISTER_BEGIN), sessionA, null));
    }

    @Test
    void B5_パスキー保有者はパスワード経路では再認証できずパスキーを案内される() throws Exception {
        MvcResult method = perform(get(REAUTH_METHOD), sessionA, null);
        assertThat(method.getResponse().getStatus()).as(body(method)).isEqualTo(200);
        assertThat(data(method).path("method").asText()).isEqualTo("PASSKEY");

        assert4xx(perform(post(REAUTH_FACTOR), sessionA, Map.of("currentPassword", PASSWORD)));
        assert4xx(deleteCredential(sessionA, credential.getId()));
    }

    @Test
    void B5_何も提示しない再認証は通らない() throws Exception {
        credentials.deleteAll(credentials.findByUserId(userId));
        assertThat(data(perform(get(REAUTH_METHOD), sessionA, null)).path("method").asText()).isEqualTo("PASSWORD");

        assert4xx(perform(post(REAUTH_FACTOR), sessionA, Map.of()));
        assert4xx(perform(post(REGISTER_BEGIN), sessionA, null));
    }

    @Test
    void B6_パスワード再認証は5回15分で制限され失敗はAUTH080() throws Exception {
        credentials.deleteAll(credentials.findByUserId(userId));
        for (int i = 0; i < 5; i++) {
            MvcResult failed = perform(post(REAUTH_FACTOR), sessionA, Map.of("currentPassword", "wrong-" + i));
            assertThat(failed.getResponse().getStatus()).as(body(failed)).isEqualTo(400);
            assertThat(errorCode(failed)).isEqualTo("AUTH_080");
        }
        MvcResult limited = perform(post(REAUTH_FACTOR), sessionA, Map.of("currentPassword", PASSWORD));
        assertThat(limited.getResponse().getStatus()).as(body(limited)).isEqualTo(429);
        assert4xx(perform(post(REGISTER_BEGIN), sessionA, null));
    }

    @Test
    void B7_再認証の成功と失敗を監査ログに記録する() throws Exception {
        credentials.deleteAll(credentials.findByUserId(userId));
        perform(post(REAUTH_FACTOR), sessionA, Map.of("currentPassword", "wrong"));
        reauthFactorOk(sessionA, Map.of("currentPassword", PASSWORD));

        // 監査は AFTER_COMMIT の非同期リスナーで記録されるため、少し待って数える
        long deadline = System.currentTimeMillis() + 10_000;
        Integer rows = 0;
        while (System.currentTimeMillis() < deadline) {
            rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND event_type LIKE '%REAUTH%'",
                    Integer.class, userId);
            if (rows != null && rows >= 2) {
                break;
            }
            Thread.onSpinWait();
        }
        assertThat(rows).as("再認証の成功・失敗それぞれ 1 行以上").isGreaterThanOrEqualTo(2);
    }

    // ─── ヘルパー ───

    private WebAuthnCredentialEntity saveCredential(long ownerId, PasskeyAssertionFixture fixture) {
        UserEntity owner = userRepository.findById(ownerId).orElseThrow();
        return credentials.save(WebAuthnCredentialEntity.builder().userId(owner.getId())
                .credentialId(fixture.credentialId()).publicKey(fixture.publicKey()).signCount(0L)
                .deviceName("試験用認証器").build());
    }

    private WebAuthnReauthenticateCompleteRequest reauth(String challenge, int flags, long signed) throws Exception {
        return authenticator.reauthAssertion(challenge, origin, rpId, flags, signed, signed);
    }

    private String reauthBegin(String token, String purpose) throws Exception {
        MvcResult result = perform(post(REAUTH_BEGIN), token, Map.of("purpose", purpose));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return data(result).path("challenge").asText();
    }

    private MvcResult reauthComplete(String token, WebAuthnReauthenticateCompleteRequest req) throws Exception {
        return perform(post(REAUTH_COMPLETE), token, req);
    }

    private void reauthOk(String token, String purpose) throws Exception {
        MvcResult result = reauthComplete(token, reauth(reauthBegin(token, purpose), UP | UV, 1));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    private void reauthFactorOk(String token, Map<String, ?> factorBody) throws Exception {
        MvcResult result = perform(post(REAUTH_FACTOR), token, factorBody);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    private MvcResult deleteCredential(String token, Long id) throws Exception {
        return perform(delete(BASE + "/credentials/" + id), token, null);
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                              String token, Object body) throws Exception {
        builder.header("Authorization", "Bearer " + token).header("User-Agent", USER_AGENT);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder).andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json(result).path("data");
    }

    private void assert4xx(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(body(result)).isBetween(400, 499);
    }
}
