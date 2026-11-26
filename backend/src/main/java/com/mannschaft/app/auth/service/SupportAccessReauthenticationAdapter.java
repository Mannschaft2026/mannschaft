package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * F10.9 サポート閲覧の許可時の本人再認証（supportaccess の port を auth ドメインが実装する）。
 *
 * <p>パスワード（現在のパスワードの照合）かパスキー（WebAuthn 再認証の署名検証）のどちらか。
 * メールのワンクリックでは成立しない。F18 提示モードの再認証フラグ（Valkey）は読み書きせず、
 * 署名検証だけを共有する（用途を分ける）。パスワードの総当たりを防ぐため試行回数を制限する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAccessReauthenticationAdapter implements SupportAccessReauthenticationVerifier {

    /** 1分あたりの試行上限（退会の再認証と同じ強さ）。 */
    private static final int MAX_ATTEMPTS_PER_MINUTE = 5;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final AuthWebAuthnService authWebAuthnService;

    @Override
    public boolean verify(Long userId, SupportAccessApproveRequest request) {
        if (userId == null || request == null) {
            return false;
        }
        // 試行回数の超過は BusinessException(AUTH_044) をそのまま伝える（再認証失敗とは区別する）
        authTokenService.checkRateLimit("mannschaft:support_access:reauth:" + userId,
                MAX_ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1));

        if (request.passkeyAssertionJson() != null && !request.passkeyAssertionJson().isBlank()) {
            return verifyPasskey(userId, request.passkeyAssertionJson());
        }
        return verifyPassword(userId, request.currentPassword());
    }

    private boolean verifyPassword(Long userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        UserEntity user = userRepository.findById(userId).orElse(null);
        // パスワード未設定（OAuth・パスキー専用）の本人はパスキーで再認証する
        if (user == null || user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    private boolean verifyPasskey(Long userId, String assertionJson) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(assertionJson);
            String credentialId = text(node, "credentialId");
            String authenticatorData = text(node, "authenticatorData");
            String clientDataJson = text(node, "clientDataJson");
            String signature = text(node, "signature");
            if (credentialId == null || authenticatorData == null || clientDataJson == null || signature == null) {
                return false;
            }
            WebAuthnReauthenticateCompleteRequest req = new WebAuthnReauthenticateCompleteRequest(
                    credentialId, authenticatorData, clientDataJson, signature, node.path("signCount").asLong(0L));
            authWebAuthnService.verifyReauthenticationAssertionOnly(userId, req);
            return true;
        } catch (BusinessException e) {
            log.warn("サポート閲覧のパスキー再認証に失敗: userId={}, code={}", userId, e.getErrorCode().getCode());
            return false;
        } catch (java.io.IOException e) {
            log.warn("サポート閲覧のパスキー再認証の入力が不正: userId={}", userId);
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
