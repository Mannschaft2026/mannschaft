package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.9 サポート閲覧の許可用のパスキー再認証（{@link AuthWebAuthnService#verifyReauthenticationAssertionOnly}）。
 *
 * <p>実際の ECDSA 署名で authenticatorData を組み立て、WebAuthn4J の検証を通す（モックで署名検証を飛ばさない）。
 * 許可は「本人がいまこの端末で本人確認した」ことの証明であるため、UV（本人確認）・UP（在席）を必須にし、
 * sign_count は署名検証済みの authenticatorData の値で判定する（同期パスキーの 0/0 は許す）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("検分差し戻し対応: サポート閲覧の許可用パスキー再認証は UV・UP 必須・signCount は authenticatorData で判定")
class AuthWebAuthnReauthStrictTest {

    private static final String RP_ID = "mannschaft.app";
    private static final String ORIGIN = "https://mannschaft.app";
    private static final Long USER_ID = 1L;
    private static final byte FLAG_UP = 0x01;
    private static final byte FLAG_UV = 0x04;

    @Mock private WebAuthnCredentialRepository webAuthnCredentialRepository;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private RoleClaimResolver roleClaimResolver;
    @Mock private StatusClaimResolver statusClaimResolver;
    @Mock private AuthService authService;
    @Mock private Clock clock;

    @InjectMocks private AuthWebAuthnService authWebAuthnService;

    private KeyPair keyPair;
    private byte[] credentialIdBytes;
    private byte[] challenge;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(authWebAuthnService, "rpId", RP_ID);
        ReflectionTestUtils.setField(authWebAuthnService, "rpOrigin", ORIGIN);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        credentialIdBytes = "credential-id-0001".getBytes(StandardCharsets.UTF_8);
        challenge = "support-access-reauth-challenge".getBytes(StandardCharsets.UTF_8);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn(b64(challenge));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private WebAuthnCredentialEntity storedCredential(long signCount) {
        EC2COSEKey coseKey = EC2COSEKey.create((ECPublicKey) keyPair.getPublic(), COSEAlgorithmIdentifier.ES256);
        byte[] cbor = new ObjectConverter().getCborConverter().writeValueAsBytes(coseKey);
        WebAuthnCredentialEntity credential = WebAuthnCredentialEntity.builder()
                .id(10L).userId(USER_ID).credentialId(b64(credentialIdBytes))
                .publicKey(b64(cbor)).signCount(signCount).deviceName("phone").build();
        given(webAuthnCredentialRepository.findByCredentialId(b64(credentialIdBytes))).willReturn(Optional.of(credential));
        return credential;
    }

    /** 実署名つきのアサーション。clientSignCount はクライアント申告値（判定に使われないことを確かめる）。 */
    private WebAuthnReauthenticateCompleteRequest assertion(byte flags, int counter, long clientSignCount) throws Exception {
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(RP_ID.getBytes(StandardCharsets.UTF_8));
        byte[] authenticatorData = ByteBuffer.allocate(37).put(rpIdHash).put(flags).putInt(counter).array();
        byte[] clientDataJson = ("{\"type\":\"webauthn.get\",\"challenge\":\"" + b64(challenge)
                + "\",\"origin\":\"" + ORIGIN + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJson);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(ByteBuffer.allocate(authenticatorData.length + clientDataHash.length)
                .put(authenticatorData).put(clientDataHash).array());
        return new WebAuthnReauthenticateCompleteRequest(b64(credentialIdBytes), b64(authenticatorData),
                b64(clientDataJson), b64(signer.sign()), clientSignCount);
    }

    private static String codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).getErrorCode().getCode();
    }

    @Test
    @DisplayName("UV=false（在席のタップだけ）のアサーションは、署名が正しくても AUTH_024 で拒否し、カウンタを更新しない")
    void UVが無いと拒否() throws Exception {
        storedCredential(0L);

        assertThatThrownBy(() -> authWebAuthnService.verifyReauthenticationAssertionOnly(USER_ID,
                assertion(FLAG_UP, 0, 99L)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("AUTH_024"));
        verify(webAuthnCredentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("UP=false のアサーションも AUTH_024 で拒否する")
    void UPが無いと拒否() throws Exception {
        storedCredential(0L);

        assertThatThrownBy(() -> authWebAuthnService.verifyReauthenticationAssertionOnly(USER_ID,
                assertion(FLAG_UV, 0, 99L)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("AUTH_024"));
    }

    @Test
    @DisplayName("UV・UP ありで、カウンタを実装しない同期パスキー（保存値0・authenticatorData 0）は成功する")
    void 同期パスキーの0と0は成功() throws Exception {
        storedCredential(0L);

        authWebAuthnService.verifyReauthenticationAssertionOnly(USER_ID,
                assertion((byte) (FLAG_UP | FLAG_UV), 0, 0L));

        ArgumentCaptor<WebAuthnCredentialEntity> saved = ArgumentCaptor.forClass(WebAuthnCredentialEntity.class);
        verify(webAuthnCredentialRepository).save(saved.capture());
        assertThat(saved.getValue().getSignCount()).isZero();
    }

    @Test
    @DisplayName("sign_count はクライアント申告ではなく authenticatorData の値で判定する（保存値5・authenticatorData 5 は AUTH_026。申告値 100 は無視）")
    void カウンタはauthenticatorDataで判定() throws Exception {
        storedCredential(5L);

        assertThatThrownBy(() -> authWebAuthnService.verifyReauthenticationAssertionOnly(USER_ID,
                assertion((byte) (FLAG_UP | FLAG_UV), 5, 100L)))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo("AUTH_026"));
        verify(webAuthnCredentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("カウンタが増えていれば成功し、保存値は authenticatorData の値に更新する（申告値ではない）")
    void カウンタ増加は成功() throws Exception {
        storedCredential(5L);

        authWebAuthnService.verifyReauthenticationAssertionOnly(USER_ID,
                assertion((byte) (FLAG_UP | FLAG_UV), 6, 1000L));

        ArgumentCaptor<WebAuthnCredentialEntity> saved = ArgumentCaptor.forClass(WebAuthnCredentialEntity.class);
        verify(webAuthnCredentialRepository).save(saved.capture());
        assertThat(saved.getValue().getSignCount()).isEqualTo(6L);
    }

    @Test
    @DisplayName("用途の分離: サポート閲覧の許可用の検証は F18 提示モードの再認証済みフラグ（webauthn_reauth_verified）を書かない")
    void F18のフラグを書かない() throws Exception {
        storedCredential(0L);

        authWebAuthnService.verifyReauthenticationAssertionOnly(USER_ID,
                assertion((byte) (FLAG_UP | FLAG_UV), 0, 0L));

        verify(valueOperations, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
