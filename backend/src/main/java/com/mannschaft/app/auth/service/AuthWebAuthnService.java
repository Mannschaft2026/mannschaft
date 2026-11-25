package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.PasskeyManageReauthMethod;
import com.mannschaft.app.auth.ReauthPurpose;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.UpdateWebAuthnCredentialRequest;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.auth.event.LoginFailedEvent;
import com.mannschaft.app.auth.event.ReauthenticationFailedEvent;
import com.mannschaft.app.auth.event.ReauthenticationSucceededEvent;
import com.mannschaft.app.auth.event.WebAuthnCredentialRemovedEvent;
import com.mannschaft.app.auth.event.WebAuthnLoginEvent;
import com.mannschaft.app.auth.event.WebAuthnLoginFailedEvent;
import com.mannschaft.app.auth.event.WebAuthnRegisteredEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import com.webauthn4j.verifier.exception.MaliciousCounterValueException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * WebAuthn（パスキー・FIDO2）認証サービス。
 * 資格情報の登録・ログイン・管理を担当する。
 * <p>
 * WebAuthn4Jライブラリを使用した attestation / assertion 検証を実装済み。
 * </p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthWebAuthnService {

    private final WebAuthnCredentialRepository webAuthnCredentialRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final RoleClaimResolver roleClaimResolver;
    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final DomainEventPublisher eventPublisher;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CHALLENGE_KEY_PREFIX = "mannschaft:auth:webauthn_challenge:";
    /**
     * F18 提示モード追加保護用の再認証チャレンジキープレフィックス（設計書 §9.6）。
     * 既存のログイン用 {@code webauthn_challenge:login:*} とは独立した名前空間にして
     * 誤って AT/RT を発行する completeLogin との混線を防ぐ。
     */
    private static final String REAUTH_CHALLENGE_KEY_PREFIX = "mannschaft:auth:webauthn_reauth_challenge:";
    /**
     * 再認証済みフラグのキープレフィックス（F18 設計書 §9.6 / POINT_CARD_009、CMP-261021-1338）。
     * キーは {@code prefix + purpose + ":" + userId + ":" + sessionHash}。値は固定文字列 "1"、TTL は 5 分。
     * 用途（A7）・ユーザー・再認証したセッション（B4）に束ね、消費は getAndDelete 一発（B3）。
     */
    private static final String REAUTH_VERIFIED_KEY_PREFIX = "mannschaft:auth:webauthn_reauth_verified:";
    private static final char REAUTH_CHALLENGE_SEPARATOR = ':';
    private static final int CHALLENGE_TTL_MINUTES = 5;
    /** 再認証済みフラグの有効期間（分）。{@link Auth2faService} が期限表示に使う。 */
    static final int REAUTH_VERIFIED_TTL_MINUTES = 5;
    private static final String RP_NAME = "Mannschaft";

    private final WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();

    @Value("${mannschaft.webauthn.rp-id:mannschaft.app}")
    private String rpId;

    @Value("${mannschaft.webauthn.origin:https://mannschaft.app}")
    private String rpOrigin;

    /**
     * WebAuthn登録を開始する。チャレンジを生成してValkeyに保存する。
     *
     * <p>登録は再認証（{@link ReauthPurpose#PASSKEY_MANAGE}）済みのセッションに限る（B1/B2）。
     * ここではフラグを消費せず存在だけを確認し、消費は {@link #completeRegister} で行う。</p>
     *
     * @param userId      ユーザーID
     * @param sessionHash 操作中のセッション
     * @return 登録開始レスポンス
     */
    @Transactional
    public ApiResponse<WebAuthnRegisterBeginResponse> beginRegister(Long userId, String sessionHash) {
        if (!hasReauthentication(userId, sessionHash, ReauthPurpose.PASSKEY_MANAGE)) {
            throw new BusinessException(AuthErrorCode.AUTH_087);
        }
        UserEntity user = findUserOrThrow(userId);

        // チャレンジ生成
        String challenge = generateChallenge();

        // Valkey に保存（5分有効）
        String challengeKey = CHALLENGE_KEY_PREFIX + "register:" + userId;
        redisTemplate.opsForValue().set(challengeKey, challenge, CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);

        WebAuthnRegisterBeginResponse response = new WebAuthnRegisterBeginResponse(
                challenge, rpId, RP_NAME, userId, user.getLastName() + " " + user.getFirstName());

        return ApiResponse.of(response);
    }

    /**
     * WebAuthn登録を完了する。
     * <ol>
     *   <li>再認証済みフラグの消費（getAndDelete 一発。無ければ AUTH_087。B1/B3/B4）</li>
     *   <li>チャレンジ検証（getAndDelete で原子的に 1 回限り。B8）</li>
     *   <li>attestation 検証（UV・UP 必須。B8）</li>
     *   <li>credential_id重複チェック</li>
     *   <li>WebAuthnCredentialEntity保存</li>
     *   <li>WebAuthnRegisteredEvent発行</li>
     * </ol>
     *
     * @param userId      ユーザーID
     * @param sessionHash 操作中のセッション
     * @param req         登録完了リクエスト
     * @return メッセージレスポンス
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public ApiResponse<MessageResponse> completeRegister(
            Long userId, String sessionHash, WebAuthnRegisterCompleteRequest req) {
        // 0. 再認証済みフラグを 1 回限りで消費する（同時 2 リクエストのうち成功は 1 つだけ）
        if (!consumeReauthentication(userId, sessionHash, ReauthPurpose.PASSKEY_MANAGE)) {
            throw new BusinessException(AuthErrorCode.AUTH_087);
        }

        // 1. チャレンジ検証（原子的に取得して削除し、同一チャレンジの並行・再送完了を 1 回に限定する）
        String challengeKey = CHALLENGE_KEY_PREFIX + "register:" + userId;
        String storedChallenge = redisTemplate.opsForValue().getAndDelete(challengeKey);
        if (storedChallenge == null) {
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        // WebAuthn4J attestation 検証 + CBOR公開鍵抽出 + 保存
        try {
            byte[] attestationObject = Base64.getUrlDecoder().decode(req.getAttestationObject());
            byte[] clientDataJson = Base64.getUrlDecoder().decode(req.getClientDataJson());

            RegistrationRequest registrationRequest = new RegistrationRequest(attestationObject, clientDataJson);

            ServerProperty serverProperty = new ServerProperty(
                    new Origin(rpOrigin),
                    rpId,
                    new DefaultChallenge(Base64.getUrlDecoder().decode(storedChallenge)),
                    null
            );
            // UV・UP を必須にする（UV 無しの認証器で本人確認なしにパスキーを増やさせない。B8）
            RegistrationParameters registrationParameters = new RegistrationParameters(
                    serverProperty, null, true, true);

            RegistrationData registrationData = webAuthnManager.parse(registrationRequest);
            webAuthnManager.validate(registrationData, registrationParameters);

            // RegistrationData から COSEKey を抽出して CBOR シリアライズ
            // FE から送られる getPublicKey() は DER(SubjectPublicKeyInfo)形式のため使用不可
            com.webauthn4j.data.attestation.authenticator.COSEKey parsedCoseKey =
                    registrationData.getAttestationObject()
                            .getAuthenticatorData()
                            .getAttestedCredentialData()
                            .getCOSEKey();
            com.webauthn4j.converter.util.ObjectConverter regObjectConverter =
                    new com.webauthn4j.converter.util.ObjectConverter();
            byte[] coseKeyBytes = regObjectConverter.getCborConverter().writeValueAsBytes(parsedCoseKey);
            String coseKeyB64url = Base64.getUrlEncoder().withoutPadding().encodeToString(coseKeyBytes);

            // 認証器が割り当てた credentialId を取得
            byte[] parsedCredIdBytes = registrationData.getAttestationObject()
                    .getAuthenticatorData()
                    .getAttestedCredentialData()
                    .getCredentialId();
            String parsedCredIdB64url = Base64.getUrlEncoder().withoutPadding().encodeToString(parsedCredIdBytes);

            // AAGUID を RegistrationData から抽出（null の場合はゼロ UUID）
            com.webauthn4j.data.attestation.authenticator.AAGUID parsedAaguid =
                    registrationData.getAttestationObject()
                            .getAuthenticatorData()
                            .getAttestedCredentialData()
                            .getAaguid();
            String aaguidStr = (parsedAaguid != null && parsedAaguid.getValue() != null)
                    ? parsedAaguid.getValue().toString()
                    : "00000000-0000-0000-0000-000000000000";

            // 2. credential_id重複チェック
            if (webAuthnCredentialRepository.findByCredentialId(parsedCredIdB64url).isPresent()) {
                throw new BusinessException(AuthErrorCode.AUTH_025);
            }

            // 3. WebAuthnCredentialEntity保存
            WebAuthnCredentialEntity credential = WebAuthnCredentialEntity.builder()
                    .userId(userId)
                    .credentialId(parsedCredIdB64url)
                    .publicKey(coseKeyB64url)
                    .signCount(0L)
                    .deviceName(req.getDeviceName())
                    .aaguid(aaguidStr)
                    .build();
            webAuthnCredentialRepository.save(credential);

        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            log.warn("WebAuthn attestation検証失敗: userId={}", userId, e);
            throw new BusinessException(AuthErrorCode.AUTH_024, e);
        }

        // 4. イベント発行
        eventPublisher.publish(new WebAuthnRegisteredEvent(userId, req.getDeviceName()));

        return ApiResponse.of(MessageResponse.of("WebAuthn資格情報を登録しました"));
    }

    /**
     * WebAuthnログインを開始する。登録済みcredential一覧とチャレンジを返す。
     *
     * @param email ユーザーのメールアドレス
     * @return ログイン開始レスポンス
     */
    public ApiResponse<WebAuthnLoginBeginResponse> beginLogin(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));

        // 1. ユーザーの登録済みcredential一覧取得
        List<WebAuthnCredentialEntity> credentials = webAuthnCredentialRepository.findByUserId(user.getId());
        if (credentials.isEmpty()) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        List<String> allowCredentials = credentials.stream()
                .map(WebAuthnCredentialEntity::getCredentialId)
                .collect(Collectors.toList());

        // 2. チャレンジ生成 → Valkey保存（5分有効）
        String challenge = generateChallenge();
        String challengeKey = CHALLENGE_KEY_PREFIX + "login:" + user.getId();
        redisTemplate.opsForValue().set(challengeKey, challenge, CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);

        WebAuthnLoginBeginResponse response = new WebAuthnLoginBeginResponse(
                challenge, rpId, allowCredentials, 300000L);

        return ApiResponse.of(response);
    }

    /**
     * WebAuthnログインを完了する。
     * <ol>
     *   <li>チャレンジ検証</li>
     *   <li>credential_id検索</li>
     *   <li>sign_count検証（リプレイ攻撃防止）</li>
     *   <li>sign_count更新 + lastUsedAt更新</li>
     *   <li>Access Token + Refresh Token発行</li>
     *   <li>LoginSuccessEvent発行</li>
     * </ol>
     * <p>
     * WebAuthn4Jによる署名検証を実施する。
     * </p>
     *
     * @param req       ログイン完了リクエスト
     * @param ipAddress リクエスト元IPアドレス
     * @param userAgent ユーザーエージェント
     * @return トークンレスポンス
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public ApiResponse<TokenResponse> completeLogin(
            WebAuthnLoginCompleteRequest req, String ipAddress, String userAgent) {

        // 2. credential_id検索
        WebAuthnCredentialEntity credential = webAuthnCredentialRepository
                .findByCredentialId(req.getCredentialId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));

        Long userId = credential.getUserId();

        // 1. チャレンジ検証
        String challengeKey = CHALLENGE_KEY_PREFIX + "login:" + userId;
        String storedChallenge = redisTemplate.opsForValue().getAndDelete(challengeKey);
        if (storedChallenge == null) {
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        // チャレンジを原子的に消費し、同じassertionの並行完了を1回に限定する。
        long verifiedSignCount;
        // WebAuthn4J 署名検証
        try {
            byte[] credentialIdBytes = Base64.getUrlDecoder().decode(req.getCredentialId());
            byte[] authenticatorData = Base64.getUrlDecoder().decode(req.getAuthenticatorData());
            byte[] clientDataJson = Base64.getUrlDecoder().decode(req.getClientDataJson());
            byte[] signature = Base64.getUrlDecoder().decode(req.getSignature());

            AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                    credentialIdBytes, authenticatorData, clientDataJson, signature);

            ServerProperty serverProperty = new ServerProperty(
                    new Origin(rpOrigin),
                    rpId,
                    new DefaultChallenge(Base64.getUrlDecoder().decode(storedChallenge)),
                    null
            );

            // 保存済み公開鍵からAuthenticatorを構築
            com.webauthn4j.converter.util.ObjectConverter objectConverter =
                    new com.webauthn4j.converter.util.ObjectConverter();
            com.webauthn4j.data.attestation.authenticator.COSEKey coseKey =
                    objectConverter.getCborConverter().readValue(
                            Base64.getUrlDecoder().decode(credential.getPublicKey()),
                            com.webauthn4j.data.attestation.authenticator.COSEKey.class);
            // AAGUID を DB から復元（null の場合はゼロ UUID を使用）
            String storedAaguidStr = credential.getAaguid();
            com.webauthn4j.data.attestation.authenticator.AAGUID aaguid =
                    (storedAaguidStr != null)
                    ? new com.webauthn4j.data.attestation.authenticator.AAGUID(java.util.UUID.fromString(storedAaguidStr))
                    : com.webauthn4j.data.attestation.authenticator.AAGUID.ZERO;
            com.webauthn4j.authenticator.Authenticator authenticator =
                    new com.webauthn4j.authenticator.AuthenticatorImpl(
                            new AttestedCredentialData(aaguid, credentialIdBytes, coseKey),
                            null, credential.getSignCount());

            AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                    serverProperty, authenticator, null, true, true);

            AuthenticationData authenticationData = webAuthnManager.parse(authenticationRequest);
            webAuthnManager.validate(authenticationData, authenticationParameters);
            verifiedSignCount = authenticationData.getAuthenticatorData().getSignCount();
        } catch (MaliciousCounterValueException e) {
            eventPublisher.publish(new WebAuthnLoginFailedEvent(userId, ipAddress, userAgent, req.getCredentialId()));
            throw new BusinessException(AuthErrorCode.AUTH_026, e);
        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            log.warn("WebAuthn署名検証失敗: credentialId={}", req.getCredentialId(), e);
            eventPublisher.publish(new WebAuthnLoginFailedEvent(userId, ipAddress, userAgent, req.getCredentialId()));
            throw new BusinessException(AuthErrorCode.AUTH_024, e);
        }

        // カウンタを実装しない同期パスキーの0/0は許す。いずれかが正値なら非増加を拒否する。
        // クライアント申告のsignCountではなく、署名検証済みauthenticatorDataだけを信用する。
        if ((verifiedSignCount != 0 || credential.getSignCount() != 0)
                && verifiedSignCount <= credential.getSignCount()) {
            eventPublisher.publish(new WebAuthnLoginFailedEvent(userId, ipAddress, userAgent, req.getCredentialId()));
            throw new BusinessException(AuthErrorCode.AUTH_026);
        }

        // 署名検証済みの入口で利用状態を確認し、復帰後の状態からJWT rolesを解決する。
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));
        if (user.getStatus() == UserEntity.UserStatus.PENDING_VERIFICATION) {
            eventPublisher.publish(new LoginFailedEvent(
                    user.getEmail(), ipAddress, userAgent, "PENDING_VERIFICATION"));
            throw new BusinessException(AuthErrorCode.AUTH_002);
        }
        if (user.getStatus() == UserEntity.UserStatus.FROZEN) {
            eventPublisher.publish(new LoginFailedEvent(user.getEmail(), ipAddress, userAgent, "FROZEN"));
            throw new BusinessException(AuthErrorCode.AUTH_003);
        }
        // 既存パスワードログインと同じValkey障害時fail-open方針でロックを確認する。
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(AuthService.ACCOUNT_LOCK_KEY_PREFIX + userId))) {
                eventPublisher.publish(new LoginFailedEvent(user.getEmail(), ipAddress, userAgent, "ACCOUNT_LOCKED"));
                throw new BusinessException(AuthErrorCode.AUTH_003);
            }
        } catch (DataAccessException e) {
            log.warn("アカウントロックチェックをスキップ（Valkey接続失敗）: {}", e.getMessage());
        }
        if (user.getStatus() == UserEntity.UserStatus.ARCHIVED) {
            user.unarchive();
            // RoleClaimResolverのnative queryがACTIVE状態を読む前にDBへ反映する。
            userRepository.saveAndFlush(user);
        }

        credential.recordAuthentication(verifiedSignCount, clock.instant());
        TokenResponse tokenResponse = authService.completePasskeyLogin(
                user, ipAddress, userAgent, roleClaimResolver.resolveRoles(userId));

        // 共通のLoginSuccessEventはAuthServiceから1回だけ発行する。
        eventPublisher.publish(new WebAuthnLoginEvent(userId, ipAddress, userAgent, req.getCredentialId()));
        return ApiResponse.of(tokenResponse);
    }

    /**
     * ユーザーの登録済みWebAuthn資格情報一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 資格情報レスポンスリスト
     */
    public ApiResponse<List<WebAuthnCredentialResponse>> getCredentials(Long userId) {
        List<WebAuthnCredentialResponse> credentials = webAuthnCredentialRepository.findByUserId(userId).stream()
                .map(this::toCredentialResponse)
                .collect(Collectors.toList());

        return ApiResponse.of(credentials);
    }

    /**
     * WebAuthn資格情報のデバイス名を更新する。
     *
     * @param userId       ユーザーID
     * @param credentialId 資格情報ID
     * @param req          更新リクエスト
     * @return 更新後の資格情報レスポンス
     */
    @Transactional
    public ApiResponse<WebAuthnCredentialResponse> updateCredentialName(
            Long userId, Long credentialId, UpdateWebAuthnCredentialRequest req) {

        WebAuthnCredentialEntity credential = webAuthnCredentialRepository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));

        // 所有者チェック
        if (!credential.getUserId().equals(userId)) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        WebAuthnCredentialEntity updated = credential.toBuilder()
                .deviceName(req.getDeviceName())
                .build();
        webAuthnCredentialRepository.save(updated);

        return ApiResponse.of(toCredentialResponse(updated));
    }

    /**
     * WebAuthn資格情報を削除する。
     *
     * <p>削除は再認証（{@link ReauthPurpose#PASSKEY_MANAGE}）済みのセッションに限り、フラグは getAndDelete 一発で
     * 消費する（B1〜B4）。所有者の確認を先に行い、他人・不在の ID は再認証の有無に関わらず AUTH_024 で区別しない。</p>
     *
     * @param userId       ユーザーID
     * @param sessionHash  操作中のセッション
     * @param credentialId 資格情報ID
     */
    @Transactional
    public void deleteCredential(Long userId, String sessionHash, Long credentialId) {
        WebAuthnCredentialEntity credential = webAuthnCredentialRepository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));

        // 所有者チェック
        if (!credential.getUserId().equals(userId)) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        if (!consumeReauthentication(userId, sessionHash, ReauthPurpose.PASSKEY_MANAGE)) {
            throw new BusinessException(AuthErrorCode.AUTH_087);
        }

        webAuthnCredentialRepository.delete(credential);

        // イベント発行
        eventPublisher.publish(new WebAuthnCredentialRemovedEvent(userId, credentialId));
    }

    // ─────────────────────────────────────────────
    // 再認証（F18 提示モード追加保護 設計書 §9.6 / CMP-261021-1339・1338 パスキー登録・削除）
    // ─────────────────────────────────────────────

    /**
     * 用途付きの WebAuthn 再認証を開始する。
     *
     * <p>認証済みユーザー本人を対象にする。チャレンジは {@code webauthn_reauth_challenge:{userId}} に
     * 「用途:チャレンジ」の形で 5 分 TTL 保存し、用途をチャレンジに束ねる（A7）。AT/RT は発行しない。</p>
     *
     * @param userId  現在のユーザー ID
     * @param purpose 再認証の用途
     * @return 再認証開始レスポンス
     */
    @Transactional
    public ApiResponse<WebAuthnReauthenticateBeginResponse> beginReauthenticate(Long userId, ReauthPurpose purpose) {
        if (purpose == null) {
            throw new BusinessException(AuthErrorCode.AUTH_087);
        }
        UserEntity user = findUserOrThrow(userId);

        // 登録済み credential が無いとそもそも再認証できない。明確に AUTH_024 で失敗させる。
        List<WebAuthnCredentialEntity> credentials = webAuthnCredentialRepository.findByUserId(user.getId());
        if (credentials.isEmpty()) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        List<String> allowCredentials = credentials.stream()
                .map(WebAuthnCredentialEntity::getCredentialId)
                .collect(Collectors.toList());

        String challenge = generateChallenge();
        String challengeKey = REAUTH_CHALLENGE_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(challengeKey, purpose.name() + REAUTH_CHALLENGE_SEPARATOR + challenge,
                CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);

        WebAuthnReauthenticateBeginResponse response = new WebAuthnReauthenticateBeginResponse(
                challenge, rpId, allowCredentials, userId, 60_000L);
        return ApiResponse.of(response);
    }

    /**
     * WebAuthn 再認証を完了し、開始時の用途の再認証済みフラグを再認証したセッションに限って立てる。
     *
     * <ol>
     *   <li>credential_id 検索 + 所有者確認（他人の credential は AUTH_024。チャレンジはまだ消費しない。A3）</li>
     *   <li>チャレンジの原子的な取得・削除（getAndDelete。再送・並行は AUTH_027。A4）</li>
     *   <li>WebAuthn4J による署名検証（UV・UP 必須。A1）</li>
     *   <li>sign_count 判定（検証済み authenticatorData の値のみ信用。0/0 許容。A5）</li>
     *   <li>再認証済みフラグを 5 分 TTL で記録（用途・ユーザー・セッションに束縛。A7/B4）</li>
     * </ol>
     *
     * <p>AT/RT は発行しない。成功・失敗は監査イベントで記録する（B7）。</p>
     *
     * @param userId      現在のユーザー ID
     * @param sessionHash 再認証したセッション（{@code SecurityUtils.getCurrentSessionHash()}）
     * @param req         再認証完了リクエスト
     * @return 期限 (verifiedUntil) のみを含むレスポンス
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public ApiResponse<WebAuthnReauthenticateCompleteResponse> completeReauthenticate(
            Long userId, String sessionHash, WebAuthnReauthenticateCompleteRequest req) {
        if (sessionHash == null || sessionHash.isBlank()) {
            // セッションを特定できないとフラグを束縛できない
            throw new BusinessException(AuthErrorCode.AUTH_087);
        }

        // 1. credential_id 検索 + 所有者確認（チャレンジ消費より前。他人の credential で本人のチャレンジを焼かせない）
        WebAuthnCredentialEntity credential = webAuthnCredentialRepository
                .findByCredentialId(req.getCredentialId())
                .filter(c -> userId.equals(c.getUserId()))
                .orElse(null);
        if (credential == null) {
            publishReauthFailed(userId, null);
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        // 2. チャレンジを原子的に取得・削除（同一チャレンジの並行・再送は 1 回に限る）
        String stored = redisTemplate.opsForValue().getAndDelete(REAUTH_CHALLENGE_KEY_PREFIX + userId);
        if (stored == null) {
            publishReauthFailed(userId, null);
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }
        int separator = stored.indexOf(REAUTH_CHALLENGE_SEPARATOR);
        ReauthPurpose purpose;
        String storedChallenge;
        try {
            purpose = ReauthPurpose.valueOf(stored.substring(0, separator));
            storedChallenge = stored.substring(separator + 1);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            // 想定外の形式（過去版が書いた値など）は再認証をやり直させる
            publishReauthFailed(userId, null);
            throw new BusinessException(AuthErrorCode.AUTH_027, e);
        }

        // 3. WebAuthn4J 署名検証
        long verifiedSignCount;
        try {
            byte[] credentialIdBytes = Base64.getUrlDecoder().decode(req.getCredentialId());
            byte[] authenticatorData = Base64.getUrlDecoder().decode(req.getAuthenticatorData());
            byte[] clientDataJson = Base64.getUrlDecoder().decode(req.getClientDataJson());
            byte[] signature = Base64.getUrlDecoder().decode(req.getSignature());

            AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                    credentialIdBytes, authenticatorData, clientDataJson, signature);

            ServerProperty serverProperty = new ServerProperty(
                    new Origin(rpOrigin),
                    rpId,
                    new DefaultChallenge(Base64.getUrlDecoder().decode(storedChallenge)),
                    null
            );

            com.webauthn4j.converter.util.ObjectConverter objectConverter =
                    new com.webauthn4j.converter.util.ObjectConverter();
            com.webauthn4j.data.attestation.authenticator.COSEKey coseKey =
                    objectConverter.getCborConverter().readValue(
                            Base64.getUrlDecoder().decode(credential.getPublicKey()),
                            com.webauthn4j.data.attestation.authenticator.COSEKey.class);
            com.webauthn4j.authenticator.Authenticator authenticator =
                    new com.webauthn4j.authenticator.AuthenticatorImpl(
                            new AttestedCredentialData(
                                    (credential.getAaguid() != null)
                                    ? new com.webauthn4j.data.attestation.authenticator.AAGUID(java.util.UUID.fromString(credential.getAaguid()))
                                    : com.webauthn4j.data.attestation.authenticator.AAGUID.ZERO,
                                    credentialIdBytes, coseKey),
                            null, credential.getSignCount());

            // UV・UP を必須にする（ログイン検証と同じ正規形。UV 無しの署名で再認証を通さない。A1）
            AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                    serverProperty, authenticator, null, true, true);

            AuthenticationData authenticationData = webAuthnManager.parse(authenticationRequest);
            webAuthnManager.validate(authenticationData, authenticationParameters);
            verifiedSignCount = authenticationData.getAuthenticatorData().getSignCount();
        } catch (MaliciousCounterValueException e) {
            publishReauthFailed(userId, purpose);
            throw new BusinessException(AuthErrorCode.AUTH_026, e);
        } catch (DataConversionException | VerificationException | IllegalArgumentException e) {
            log.warn("WebAuthn 再認証署名検証失敗: userId={}, credentialId={}",
                    userId, req.getCredentialId(), e);
            publishReauthFailed(userId, purpose);
            throw new BusinessException(AuthErrorCode.AUTH_024, e);
        }

        // 4. sign_count 判定。カウンタを実装しない同期パスキーの 0/0 は許す。いずれかが正値なら非増加を拒否する。
        // クライアント申告の signCount ではなく、署名検証済み authenticatorData だけを信用する（A5）。
        if ((verifiedSignCount != 0 || credential.getSignCount() != 0)
                && verifiedSignCount <= credential.getSignCount()) {
            publishReauthFailed(userId, purpose);
            throw new BusinessException(AuthErrorCode.AUTH_026);
        }

        // sign_count 更新 + lastUsedAt 更新（ログイン時と同じパターン）
        credential.recordAuthentication(verifiedSignCount, clock.instant());

        // 5. 再認証済みフラグを 5 分 TTL で記録
        markReauthenticated(userId, sessionHash, purpose);

        eventPublisher.publish(new ReauthenticationSucceededEvent(userId, purpose, PasskeyManageReauthMethod.PASSKEY));
        OffsetDateTime verifiedUntil = OffsetDateTime
                .ofInstant(clock.instant(), clock.getZone()).plusMinutes(REAUTH_VERIFIED_TTL_MINUTES);
        return ApiResponse.of(new WebAuthnReauthenticateCompleteResponse(verifiedUntil));
    }

    /**
     * 再認証済みフラグを 5 分 TTL で立てる（パスワード／メールOTP 経路の再認証成功時に {@link Auth2faService} から呼ぶ）。
     *
     * @param userId      ユーザー ID
     * @param sessionHash 再認証したセッション（特定できなければ AUTH_087）
     * @param purpose     用途
     */
    public void markReauthenticated(Long userId, String sessionHash, ReauthPurpose purpose) {
        // セッションを特定できない場合はキー生成で AUTH_087 とし、Valkey には何も書かない
        String key = reauthVerifiedKey(userId, sessionHash, purpose);
        redisTemplate.opsForValue().set(key, "1", REAUTH_VERIFIED_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 再認証済みフラグを getAndDelete 一発で 1 回限り消費する（B3/B4/A7）。
     * 存在確認と削除を分けないため、同時 2 リクエストのうち消費できるのは 1 つだけ。
     *
     * @param userId      ユーザー ID
     * @param sessionHash 操作中のセッション
     * @param purpose     用途
     * @return 消費できたら true
     */
    public boolean consumeReauthentication(Long userId, String sessionHash, ReauthPurpose purpose) {
        if (userId == null || sessionHash == null || sessionHash.isBlank() || purpose == null) {
            return false;
        }
        return redisTemplate.opsForValue().getAndDelete(reauthVerifiedKey(userId, sessionHash, purpose)) != null;
    }

    /** 再認証済みフラグが存在するか（消費しない。登録開始の事前ゲート用）。 */
    private boolean hasReauthentication(Long userId, String sessionHash, ReauthPurpose purpose) {
        if (userId == null || sessionHash == null || sessionHash.isBlank() || purpose == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(reauthVerifiedKey(userId, sessionHash, purpose)));
    }

    private static String reauthVerifiedKey(Long userId, String sessionHash, ReauthPurpose purpose) {
        if (userId == null || sessionHash == null || sessionHash.isBlank() || purpose == null) {
            throw new BusinessException(AuthErrorCode.AUTH_087);
        }
        return REAUTH_VERIFIED_KEY_PREFIX + purpose.name() + ":" + userId + ":" + sessionHash;
    }

    /**
     * 再認証の失敗を監査イベントで記録する。失敗は例外で応答し TX がロールバックされるため、
     * リスナー側は AFTER_COMPLETION で受ける（{@code AuditLogEventListener#handleReauthenticationFailed}）。
     */
    private void publishReauthFailed(Long userId, ReauthPurpose purpose) {
        eventPublisher.publish(new ReauthenticationFailedEvent(userId, purpose, PasskeyManageReauthMethod.PASSKEY));
    }

    // === ヘルパーメソッド ===

    /**
     * WebAuthnチャレンジを生成する。
     */
    private String generateChallenge() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * ユーザーを取得する。
     */
    private UserEntity findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));
    }

    /**
     * WebAuthnCredentialEntity をレスポンスDTOに変換する。
     */
    private WebAuthnCredentialResponse toCredentialResponse(WebAuthnCredentialEntity entity) {
        return new WebAuthnCredentialResponse(
                entity.getId(),
                entity.getCredentialId(),
                entity.getDeviceName(),
                entity.getAaguid(),
                entity.getLastUsedAt(),
                entity.getCreatedAt());
    }
}
