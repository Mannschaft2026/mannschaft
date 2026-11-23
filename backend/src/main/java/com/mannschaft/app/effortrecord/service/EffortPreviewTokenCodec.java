package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EncryptionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * F06.4.1 共有 previewToken（API 契約 §4）。
 *
 * <p>owner・原本・contentRevision・原本の @Version と shareVersion・team・加入期間 ID・共有定義の revision と hash・
 * 正規化した command（mapping と allowlist）・projection hash・予約 share UUID・失効時刻を束ね、AES-256-GCM で暗号化・認証した
 * opaque 文字列（{@link EncryptionService}）。preview は DB へ書かないため、共有 PUT は token に入った command で
 * 共有内容を作り直し、projection hash の一致を確かめる。有効期間は {@value #TTL_MINUTES} 分。</p>
 *
 * <p>改竄・期限切れ・束縛不一致（別の原本・別の利用者・別のチーム・版の変化）はすべて {@code COMMON_003}（409、preview の失効）。
 * token 自体はログ・応答メッセージへ載せない（AC-61）。</p>
 */
@Component
public class EffortPreviewTokenCodec {

    /** 有効期間（分）。 */
    public static final long TTL_MINUTES = 10;
    private static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);
    private static final int MAX_LENGTH = 16384;
    private static final String VERSION = "p1";

    private final EncryptionService encryption;
    private final Clock clock;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public EffortPreviewTokenCodec(EncryptionService encryption, @Qualifier("utcClock") Clock clock) {
        this.encryption = encryption;
        this.clock = clock;
    }

    /**
     * token に束縛する内容。
     *
     * @param mappingsJson 正規化した mapping の canonical JSON
     * @param selectedJson 正規化した allowlist の canonical JSON
     */
    public record Claims(String v, Long ownerUserId, UUID recordId, long recordVersion, long contentRevision,
                         long shareVersion, Long teamId, Long grantMembershipId, UUID definitionUuid,
                         int definitionRevision, String definitionHash, String mappingsJson, String selectedJson,
                         String projectionHash, UUID shareId, long expiresAtEpochMilli) {
    }

    /** 発行した token と失効時刻。 */
    public record Issued(String token, Instant expiresAt) {
    }

    /** claims の v と失効時刻は本メソッドが決める。 */
    public Issued issue(Claims claims) {
        Instant expiresAt = Instant.ofEpochMilli(clock.instant().plus(TTL).toEpochMilli());
        Claims sealed = new Claims(VERSION, claims.ownerUserId(), claims.recordId(), claims.recordVersion(),
                claims.contentRevision(), claims.shareVersion(), claims.teamId(), claims.grantMembershipId(),
                claims.definitionUuid(), claims.definitionRevision(), claims.definitionHash(), claims.mappingsJson(),
                claims.selectedJson(), claims.projectionHash(), claims.shareId(), expiresAt.toEpochMilli());
        try {
            String plain = mapper.writeValueAsString(sealed);
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(encryption.encrypt(plain).getBytes(StandardCharsets.US_ASCII));
            return new Issued(token, expiresAt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("previewToken を発行できません", e);
        }
    }

    /**
     * token を復号し、本人・原本の束縛と有効期限を検証する（それ以外の束縛は呼出し側が現在の状態と突き合わせる）。
     *
     * @throws BusinessException 409 COMMON_003（改竄・期限切れ・別の本人・別の原本）
     */
    public Claims verify(String token, Long ownerUserId, UUID recordId) {
        if (token == null || token.isBlank() || token.length() > MAX_LENGTH) {
            throw expired();
        }
        Claims claims;
        try {
            String standard = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
            claims = mapper.readValue(encryption.decrypt(standard), Claims.class);
        } catch (JsonProcessingException | RuntimeException e) {
            // 復号失敗（改竄・鍵違い）・書式不正はすべて preview の失効と同じ扱い。入力値は記録しない。
            throw expired();
        }
        if (!VERSION.equals(claims.v()) || Instant.ofEpochMilli(claims.expiresAtEpochMilli()).isBefore(clock.instant())
                || !ownerUserId.equals(claims.ownerUserId()) || !recordId.equals(claims.recordId())) {
            throw expired();
        }
        return claims;
    }

    /** preview の失効（再 preview を求める）。 */
    public static BusinessException expired() {
        return new BusinessException(CommonErrorCode.COMMON_003);
    }
}
