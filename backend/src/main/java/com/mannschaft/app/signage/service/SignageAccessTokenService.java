package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * デジタルサイネージ アクセストークン管理サービス。
 * トークンの発行・一覧・無効化・検証を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignageAccessTokenService {

    private final SignageAccessTokenRepository tokenRepository;
    private final SignageScreenRepository screenRepository;
    private final AccessControlService accessControlService;
    private final SecretTokenVault vault;

    // ========================================
    // DTO 定義
    // ========================================

    /**
     * トークン発行リクエスト DTO。
     */
    public record IssueSignageTokenRequest(
            String name,
            /** 許可IPアドレスリスト（CIDR表記可。nullの場合は全許可） */
            List<String> allowedIps,
            /** トークン有効期限（nullの場合は無期限） */
            LocalDateTime expiredAt
    ) {}

    /**
     * トークン一覧・参照用レスポンス DTO。平文トークンは含まない（保存しているのはハッシュだけ）。
     */
    public record SignageAccessTokenResponse(
            Long id,
            Long screenId,
            String name,
            Boolean isActive,
            List<String> allowedIps,
            LocalDateTime expiredAt,
            LocalDateTime createdAt
    ) {}

    /**
     * トークン発行レスポンス DTO。平文トークンはこの応答でだけ一度返す（再表示はできない）。
     */
    public record SignageAccessTokenIssuedResponse(
            Long id,
            Long screenId,
            String token,
            String name,
            Boolean isActive,
            List<String> allowedIps,
            LocalDateTime expiredAt,
            LocalDateTime createdAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * アクセストークンを発行する。
     * 金庫で乱数トークンを生成し、SHA-256 ハッシュだけを DBに永続化する。平文はこの応答でだけ返す。
     *
     * @param screenId  画面ID
     * @param createdBy 発行者ユーザーID
     * @param req       発行リクエスト
     * @return 発行したトークンレスポンス（平文入り。この応答でだけ返る）
     */
    @Transactional
    public SignageAccessTokenIssuedResponse issueToken(Long screenId, Long createdBy, IssueSignageTokenRequest req) {
        // 画面の存在確認
        SignageScreenEntity screen = screenRepository.findByIdAndDeletedAtIsNull(screenId)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_001));

        // 認可: 当該画面スコープの ADMIN/DEPUTY_ADMIN のみトークン発行可能
        accessControlService.checkAdminOrAbove(createdBy, screen.getScopeId(), screen.getScopeType());

        // 乱数トークン生成（平文は応答にだけ載せ、保存するのはハッシュのみ）
        IssuedToken issuedToken = vault.issueBase64Url();

        // 許可IPリストをJSON文字列に変換（簡易実装: JSON配列形式）
        String allowedIpsJson = buildAllowedIpsJson(req.allowedIps());

        SignageAccessTokenEntity entity = SignageAccessTokenEntity.builder()
                .screenId(screenId)
                .tokenHash(issuedToken.hash())
                .name(req.name())
                .createdBy(createdBy)
                .allowedIps(allowedIpsJson)
                .expiredAt(req.expiredAt())
                .build();

        SignageAccessTokenEntity saved = tokenRepository.save(entity);
        log.info("サイネージトークン発行: id={}, screenId={}, name={}", saved.getId(), screenId, req.name());
        return new SignageAccessTokenIssuedResponse(
                saved.getId(),
                saved.getScreenId(),
                issuedToken.plaintext(),
                saved.getName(),
                saved.getIsActive(),
                parseAllowedIps(saved.getAllowedIps()),
                saved.getExpiredAt(),
                saved.getCreatedAt());
    }

    /**
     * 画面に紐づくトークン一覧を取得する。
     *
     * @param screenId 画面ID
     * @param actor    操作者ユーザーID
     * @return トークンレスポンス一覧
     */
    public List<SignageAccessTokenResponse> listTokens(Long screenId, Long actor) {
        // 認可: 当該画面スコープの ADMIN/DEPUTY_ADMIN のみトークン一覧取得可能
        checkScreenAdmin(screenId, actor);

        return tokenRepository.findByScreenId(screenId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * トークンを論理無効化する（isActive=false に更新）。
     *
     * @param id    トークンID
     * @param actor 操作者ユーザーID
     */
    @Transactional
    public void revokeToken(Long id, Long actor) {
        SignageAccessTokenEntity entity = findTokenOrThrow(id);

        // 認可: トークンが属する画面スコープの ADMIN/DEPUTY_ADMIN のみ無効化可能（BOLA遮断）
        checkScreenAdmin(entity.getScreenId(), actor);

        entity.deactivate();
        tokenRepository.save(entity);
        log.info("サイネージトークン無効化: id={}", id);
    }

    /**
     * トークン文字列（平文）を検証し、有効なトークンエンティティを返す。ハッシュで照合する。
     *
     * <p>受け付けるのは「存在し」「無効化されておらず（{@code isActive=true}）」
     * 「有効期限を過ぎていない」トークンのみである。いずれの条件を満たさない場合も
     * 同一の SIGNAGE_002 を返し、失敗理由を呼び出し側に区別させない。</p>
     *
     * @param token トークン文字列
     * @return 有効なトークンエンティティ
     */
    public SignageAccessTokenEntity validateToken(String token) {
        // isActive=true のトークンを取得
        SignageAccessTokenEntity entity = tokenRepository.findByTokenHashAndIsActiveTrue(vault.hash(token))
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_002));

        // 有効期限が満了したトークンは受け付けない（発行時に指定された期限を entity から判定する）
        if (entity.isExpired(LocalDateTime.now())) {
            throw new BusinessException(SignageErrorCode.SIGNAGE_002);
        }

        log.debug("サイネージトークン検証成功: id={}", entity.getId());
        return entity;
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * 指定画面のスコープに対し、操作者が ADMIN/DEPUTY_ADMIN であることを検証する。
     * 画面が存在しなければ SIGNAGE_001、権限が無ければ COMMON_002（403）をスローする。
     *
     * @param screenId 画面ID
     * @param actor    操作者ユーザーID
     */
    private void checkScreenAdmin(Long screenId, Long actor) {
        SignageScreenEntity screen = screenRepository.findByIdAndDeletedAtIsNull(screenId)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_001));
        accessControlService.checkAdminOrAbove(actor, screen.getScopeId(), screen.getScopeType());
    }

    /**
     * IDでトークンを取得する。見つからない場合は SIGNAGE_005 例外をスローする。
     */
    private SignageAccessTokenEntity findTokenOrThrow(Long id) {
        return tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_005));
    }

    /**
     * 許可IPリストをJSON配列文字列に変換する。
     * nullまたは空リストの場合はnullを返す（全許可）。
     */
    private String buildAllowedIpsJson(List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            return null;
        }
        // 簡易JSON配列生成（Jackson非依存）
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < allowedIps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(allowedIps.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     * expiredAt は永続化された Entity の値をそのまま返す（NULL は無期限）。
     */
    private SignageAccessTokenResponse toResponse(SignageAccessTokenEntity e) {
        // allowedIps JSON文字列をリストに変換（簡易実装）
        List<String> ips = parseAllowedIps(e.getAllowedIps());
        return new SignageAccessTokenResponse(
                e.getId(),
                e.getScreenId(),
                e.getName(),
                e.getIsActive(),
                ips,
                e.getExpiredAt(),
                e.getCreatedAt()
        );
    }

    /**
     * JSON配列文字列を String リストに変換する（簡易実装）。
     */
    private List<String> parseAllowedIps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        // 簡易パース: ["ip1","ip2"] → ["ip1", "ip2"]
        String trimmed = json.trim();
        if (trimmed.equals("[]")) {
            return List.of();
        }
        // ブラケットを除去してカンマ分割
        String inner = trimmed.substring(1, trimmed.length() - 1);
        return java.util.Arrays.stream(inner.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
