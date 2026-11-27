package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.ResolveTokenResponse;
import com.mannschaft.app.pointcard.service.PointCardShareTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F18 個人ポイントカードウォレット — 店主側 QR 自動特定 / 一時トークン resolve API
 * （Phase 3 第二陣 2A）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §16 / §9
 *
 * <p>本 Controller は店主側 (ADMIN / DEPUTY_ADMIN) が顧客の発行した一時トークン
 * （5 分 TTL UUID）を resolve して、自店カード ID を特定するためのエンドポイントを提供する。
 *
 * <h2>レート制限</h2>
 * <p>{@code PointCardRateLimitFilter} で適用:
 * <ul>
 *   <li>{@code POST /api/v1/organizations/{orgId}/point-cards/resolve-by-token}: 600/h</li>
 * </ul>
 *
 * <h2>責務分離</h2>
 * <p>{@link OrgPointCardStampController}（スタンプ押印 / 履歴）と独立して新設している。
 * resolve は「カード特定だけを担当して直後の押印 / 残高操作は別エンドポイント」という
 * 責務境界を明確にするため。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/point-cards")
@Tag(name = "ポイントカード 店主 QR 自動特定",
        description = "F18 Phase 3 第二陣 2A — 顧客の一時トークンから自店カードを特定")
@RequiredArgsConstructor
public class OrgPointCardResolveController {

    private final PointCardShareTokenService shareTokenService;

    /**
     * 一時トークンを resolve して cardId を特定する。
     *
     * <p>Valkey から {@code GETDEL} で原子的に取得＋削除する（再生防止 = 1 回限り消費）。
     * 期限切れ / 使用済 / 不存在は全て {@code POINT_CARD_019 TOKEN_NOT_FOUND} (404) を返す。
     *
     * @param orgId   対象組織 ID
     * @param request 一時トークンを含むリクエスト
     * @return カード特定結果（暗号化対象は一切含まない）
     */
    @PostMapping("/resolve-by-token")
    @Operation(summary = "一時トークン resolve",
            description = "顧客側で発行された 5 分 TTL の共有トークン（Base64URL 43 字）から cardId を特定する。"
                    + "GETDEL で原子的に消費するため同じトークンは 1 回しか使えない。"
                    + "暗号化対象（barcodeValue / displayName / nickname / memo）は一切返さない。"
                    + "認可: ADMIN / DEPUTY_ADMIN。レート制限: 600/h/user。")
    public ResponseEntity<ApiResponse<ResolveTokenResponse>> resolveByToken(
            @PathVariable Long orgId,
            @Valid @RequestBody ResolveByTokenRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ResolveTokenResponse response = shareTokenService.resolve(userId, orgId, request.token());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * resolve リクエスト Body。
     *
     * <p>Bean Validation では {@code @NotBlank} と長さ 43 のみチェックする。長さが合っていて
     * 文字種が違うなど形式不一致の値は、Valkey のキー（ハッシュ）に当たらず
     * 「トークン不存在」（{@code POINT_CARD_019}）として扱う（存在を漏らさない）。
     *
     * <p>旧形式の UUID（36 字）は受けない。旧トークンは移行時に TTL 5 分で自然に消え、
     * 受けても照合は必ず失敗するだけなので、許可長を増やさず 43 字だけにして単純さを優先した。
     *
     * @param token 顧客側で発行された一時トークン（Base64URL・パディング無し・43 文字）
     */
    public record ResolveByTokenRequest(
            @NotBlank
            @Size(min = 43, max = 43)
            String token
    ) {
    }
}
