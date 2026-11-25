package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 実際のES256鍵でWebAuthn assertionを生成する試験用認証器。
 * 検証器をモックせず、署名対象のrpId・UP/UV・カウンタ・clientDataを独立に変更する。
 */
final class PasskeyAssertionFixture {

    private final KeyPair keyPair;
    private final String credentialId;

    PasskeyAssertionFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        credentialId = encode(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    String credentialId() {
        return credentialId;
    }

    String publicKey() {
        return encode(new ObjectConverter().getCborConverter().writeValueAsBytes(
                EC2COSEKey.create((ECPublicKey) keyPair.getPublic(), COSEAlgorithmIdentifier.ES256)));
    }

    WebAuthnLoginCompleteRequest assertion(String challenge, String origin, String rpId,
                                           int flags, long signedCount, long reportedCount) throws Exception {
        byte[] clientData = ("{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge
                + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] authenticatorData = ByteBuffer.allocate(37)
                .put(sha256(rpId.getBytes(StandardCharsets.UTF_8)))
                .put((byte) flags)
                .putInt((int) signedCount)
                .array();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(authenticatorData);
        signer.update(sha256(clientData));
        return new WebAuthnLoginCompleteRequest(credentialId, encode(authenticatorData),
                encode(clientData), encode(signer.sign()), reportedCount);
    }

    /**
     * 再認証用 assertion（{@link #assertion} と同じ署名を再認証完了リクエストの形に詰め替える）。
     * {@code reportedCount} はクライアント申告の signCount（改竄の模擬に使う）。
     */
    WebAuthnReauthenticateCompleteRequest reauthAssertion(String challenge, String origin, String rpId,
                                                          int flags, long signedCount, long reportedCount)
            throws Exception {
        WebAuthnLoginCompleteRequest login = assertion(challenge, origin, rpId, flags, signedCount, reportedCount);
        return new WebAuthnReauthenticateCompleteRequest(login.getCredentialId(), login.getAuthenticatorData(),
                login.getClientDataJson(), login.getSignature(), reportedCount);
    }

    /**
     * 登録用の attestation（fmt=none）を生成する。{@code flags} には AT(0x40) を自動で加える。
     * UP=0x01・UV=0x04 を独立に変えて、登録時の UV 必須（B8）を試験する。
     */
    WebAuthnRegisterCompleteRequest attestation(String challenge, String origin, String rpId, int flags)
            throws Exception {
        byte[] clientData = ("{\"type\":\"webauthn.create\",\"challenge\":\"" + challenge
                + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] credentialIdBytes = Base64.getUrlDecoder().decode(credentialId);
        byte[] coseKey = Base64.getUrlDecoder().decode(publicKey());
        byte[] authenticatorData = ByteBuffer.allocate(32 + 1 + 4 + 16 + 2 + credentialIdBytes.length + coseKey.length)
                .put(sha256(rpId.getBytes(StandardCharsets.UTF_8)))
                .put((byte) (flags | 0x40))
                .putInt(0)
                .put(new byte[16])
                .putShort((short) credentialIdBytes.length)
                .put(credentialIdBytes)
                .put(coseKey)
                .array();
        Map<String, Object> attestationObject = new LinkedHashMap<>();
        attestationObject.put("fmt", "none");
        attestationObject.put("attStmt", Map.of());
        attestationObject.put("authData", authenticatorData);
        byte[] attestationBytes = new ObjectConverter().getCborConverter().writeValueAsBytes(attestationObject);
        return new WebAuthnRegisterCompleteRequest(credentialId, encode(attestationBytes), encode(clientData),
                publicKey(), "試験用認証器", null);
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
