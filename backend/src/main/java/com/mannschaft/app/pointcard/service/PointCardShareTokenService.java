package com.mannschaft.app.pointcard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.BearerSecretValkeyKey;
import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.pointcard.dto.ResolveTokenResponse;
import com.mannschaft.app.pointcard.dto.ShareTokenResponse;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — QR 自動特定 (Phase 3 第二陣 2A) サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §16 / §9
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>{@link #generate(Long, UUID)} — 顧客側で 5 分 TTL の Base64URL 43 字トークンを発行し、Valkey にはハッシュのキーで置く</li>
 *   <li>{@link #resolve(Long, String)} — 店主側で GETDEL によりトークンを 1 回限り消費して cardId 特定</li>
 * </ul>
 *
 * <h2>セキュリティ方針</h2>
 * <ul>
 *   <li>トークンは {@link SecretTokenVault#issueBase64Url()}（SecureRandom 256 bit・Base64URL 43 字）→ 推測困難</li>
 *   <li>Valkey のキーは {@code BearerSecretValkeyKey.of(接頭辞, vault.hash(平文))}。平文は発行応答の 1 回だけ返し、
 *       Valkey・ログ・ペイロードのどこにも残さない（Valkey が覗かれても使えるトークンは取れない）</li>
 *   <li>{@code SET NX EX 300} で書き込み → 衝突は超レアケースだが {@code IllegalStateException} で fail-fast</li>
 *   <li>{@code GETDEL} で原子的に取得＋削除 → 再生防止（同じトークンを 2 回 resolve できない）</li>
 *   <li>店主側 resolve では (a) トークン由来 cardId を読み、(b) その provider.organization_id と
 *       URL の orgId を一致確認することで IDOR を防ぐ</li>
 *   <li>暗号化対象（barcodeValue / displayName / nickname / memo）は resolve でも一切返さない（{@link ResolveTokenResponse} 参照）</li>
 * </ul>
 *
 * <h2>監査ログ</h2>
 * <p>本サービス自体は監査ログを記録しない（発行・解決はカジュアル操作で頻度が高くノイズになる）。
 * 後続の {@code POST /stamps} や残高操作で {@code POINT_CARD_STAMP_ISSUED} 等が記録されるため、
 * そちらで「店主側がこのカードに何をしたか」が追える。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointCardShareTokenService {

    /** Valkey キー接頭辞。{@code mannschaft:wallet:share_token:{SHA-256 hex64}}（平文トークンは置かない）。 */
    static final String KEY_PREFIX = "mannschaft:wallet:share_token:";

    /** TTL 5 分（WebAuthn 再認証と同形）。 */
    static final Duration TTL = Duration.ofMinutes(5);

    /** 店主アプリ用ディープリンクの URL スキーム接頭辞。 */
    private static final String DEEP_LINK_PREFIX = "mannschaft://wallet/share?token=";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserPointCardRepository cardRepository;
    private final PointCardProviderRepository providerRepository;
    private final AccessControlService accessControlService;
    private final SecretTokenVault vault;

    // ─────────────────────────────────────────────
    // 一時トークン発行（顧客側）
    // ─────────────────────────────────────────────

    /**
     * 顧客側で一時トークンを発行する（5 分 TTL）。
     *
     * <ol>
     *   <li><strong>本人カード確認</strong>: {@code findByIdAndUserId(cardId, userId)} で IDOR 防止</li>
     *   <li><strong>トークン発行</strong>: {@code SecretTokenVault#issueBase64Url()} で 43 字を発行</li>
     *   <li><strong>Valkey 書き込み</strong>: ハッシュのキーへ {@code SET NX EX 300}（衝突防止 + TTL 設定）</li>
     *   <li><strong>レスポンス</strong>: token / expiresAt / deepLinkUrl を返却</li>
     * </ol>
     *
     * @param userId 操作者ユーザー ID（カード保有者本人）
     * @param cardId 対象カード ID
     * @return 発行されたトークン情報
     * @throws BusinessException     カードが本人のものでない / 存在しない場合（{@code POINT_CARD_006}）
     * @throws IllegalStateException トークン衝突という超レアケース（実質発生しない）
     */
    public ShareTokenResponse generate(Long userId, UUID cardId) {
        // 1. 本人カード確認（IDOR 防止）— PointCardController と同パターン
        cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        // 2. トークン発行（平文は応答でだけ返す。Valkey には hash のキーで置く）
        IssuedToken issued = vault.issueBase64Url();
        String token = issued.plaintext();
        Instant now = Instant.now();
        Instant expiresAtInstant = now.plus(TTL);
        OffsetDateTime expiresAt = expiresAtInstant.atOffset(ZoneOffset.UTC);

        // 3. JSON ペイロード構築（LinkedHashMap で順序固定 → ログ可読性 + テスト安定）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", cardId.toString());
        payload.put("userId", userId);
        payload.put("expiresAt", expiresAt.toString());

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // ObjectMapper の writeValueAsString は通常失敗しないが、契約上 IOException を投げ得る
            throw new IllegalStateException("ShareToken payload serialization failed", e);
        }

        // 4. SET NX EX で書き込み（衝突防止 + TTL 設定）
        String key = BearerSecretValkeyKey.of(KEY_PREFIX, issued.hash());
        Boolean setOk = redisTemplate.opsForValue().setIfAbsent(key, json, TTL);
        if (!Boolean.TRUE.equals(setOk)) {
            // 256 bit の乱数が既存トークンと衝突する確率は実質 0。
            // ここに到達するのは Valkey 接続不安定など異常系のみ。fail-fast で例外。
            // キーは平文のハッシュだが、念のため値を出さずカード ID だけで追えるようにする
            log.error("ShareToken collision (extremely unlikely) or Valkey write failure: cardId={}", cardId);
            throw new IllegalStateException("Token collision; retry");
        }

        log.info("一時トークンを発行しました: userId={}, cardId={}, expiresAt={}",
                userId, cardId, expiresAt);

        return new ShareTokenResponse(token, expiresAt, DEEP_LINK_PREFIX + token);
    }

    // ─────────────────────────────────────────────
    // 一時トークン resolve（店主側）
    // ─────────────────────────────────────────────

    /**
     * 店主側で一時トークンを resolve して cardId を特定する。
     *
     * <ol>
     *   <li><strong>認可</strong>: {@code accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION")}
     *       は本メソッドの呼び出し元（Controller 側 or generate 前）で済ませる前提
     *       <em>ではなく</em>、ここで集中させる</li>
     *   <li><strong>GETDEL</strong>: Valkey から原子的に取得＋削除（再生防止）</li>
     *   <li><strong>不存在 → 019</strong>: 期限切れ / 使用済 / 不存在を区別せず全て {@code TOKEN_NOT_FOUND}</li>
     *   <li><strong>カード取得</strong>: トークン内 cardId で UserPointCard を取得</li>
     *   <li><strong>プロバイダー検証</strong>: {@code provider.organization_id == orgId} か（IDOR 防止）</li>
     *   <li><strong>レスポンス</strong>: 暗号化対象は一切含めず、cardId / providerId /
     *       providerDisplayName / providerType / last4 / currentStampCount / currentBalance を返却</li>
     * </ol>
     *
     * @param userId 操作者ユーザー ID（店主 ADMIN / DEPUTY_ADMIN）
     * @param orgId  対象組織 ID
     * @param token  顧客側で発行された一時トークン
     * @return カード特定結果
     * @throws BusinessException トークン不存在 / 期限切れ / 使用済（{@code POINT_CARD_019}）、
     *                           プロバイダー不所属（{@code POINT_CARD_011}）
     */
    public ResolveTokenResponse resolve(Long userId, Long orgId, String token) {
        // 1. 認可（ADMIN または DEPUTY_ADMIN のみ）
        accessControlService.checkAdminOrAbove(userId, orgId, "ORGANIZATION");

        // 2. GETDEL でトークン取得 + 削除（再生防止）
        String key = BearerSecretValkeyKey.of(KEY_PREFIX, vault.hash(token));
        String json = redisTemplate.opsForValue().getAndDelete(key);
        if (json == null) {
            // 期限切れ / 使用済 / 不存在を区別しない（情報漏洩防止）
            throw new BusinessException(PointCardErrorCode.TOKEN_NOT_FOUND);
        }

        // 3. JSON パース
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            // 既に削除済みなので復元できない。データ破損として 019 を返す。
            log.error("ShareToken JSON parse failed: orgId={}, resolvedBy={}", orgId, userId, e);
            throw new BusinessException(PointCardErrorCode.TOKEN_NOT_FOUND);
        }

        UUID cardId;
        try {
            cardId = UUID.fromString((String) data.get("cardId"));
        } catch (Exception e) {
            log.error("ShareToken cardId parse failed: orgId={}, resolvedBy={}", orgId, userId, e);
            throw new BusinessException(PointCardErrorCode.TOKEN_NOT_FOUND);
        }

        // 4. カード取得（IDOR 防止のため findById で良い — 店主側は自店プロバイダー紐付けで権限を担保）
        UserPointCardEntity card = cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        // 5. プロバイダー紐付け確認（外部プロバイダー / 紐付けなしカードは店主側スコープ外）
        if (card.getProviderId() == null) {
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED);
        }
        PointCardProviderEntity provider = providerRepository.findById(card.getProviderId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND));

        // 6. IDOR 防止: provider.organization_id == orgId か
        if (provider.getOrganizationId() == null
                || !provider.getOrganizationId().equals(orgId)) {
            throw new BusinessException(PointCardErrorCode.PROVIDER_NOT_OWNED);
        }

        log.info("一時トークンを resolve しました: orgId={}, resolvedBy={}, cardId={}",
                orgId, userId, cardId);

        return ResolveTokenResponse.from(card, provider);
    }
}
