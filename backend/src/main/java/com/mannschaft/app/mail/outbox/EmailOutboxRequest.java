package com.mannschaft.app.mail.outbox;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * F09.18 メール配信 enqueue リクエスト DTO。
 *
 * <p>呼び出し側ドメインの {@code @TransactionalEventListener(AFTER_COMMIT)}
 * から構築する。設計書 §6.1 の {@code EmailOutboxRequest} に対応。</p>
 *
 * @param templateKind   必須: 例 "VERIFICATION" (§11 マトリクスの 14 種から選択)
 * @param locale         必須: ja/en/zh/ko/es/de
 * @param toAddress      必須: RFC 5322 準拠の宛先メールアドレス
 * @param payloadVars    必須: HTML 禁止、構造化変数のみ (Map<String, String>)
 * @param sourceDomain   必須: 8 ドメインのいずれか
 * @param sourceEventId  任意: 業務側の冪等キー (token.id, batch_run_id 等)
 * @param idempotencyKey 任意: 省略時は sha256(user_id:template_kind:nonce)[:32] 自動生成
 * @param userId         任意: 受信者 user_id (認証前のメールでは null)
 * @param organizationId 任意: 認証メールでは null
 */
public record EmailOutboxRequest(
        String templateKind,
        String locale,
        String toAddress,
        Map<String, String> payloadVars,
        String sourceDomain,
        String sourceEventId,
        String idempotencyKey,
        Long userId,
        Long organizationId
) {

    /**
     * メールアドレス検証用の簡易正規表現。
     * RFC 5322 完全準拠ではないが、SES 送信前の基本的なバリデーションには十分。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"
    );

    /**
     * 宛先メールアドレスの形式が outbox の受け付ける形式か判定する（{@code enqueue}/{@code enqueueAll} と同一判定）。
     * 呼び出し側が enqueueAll 前に不正アドレスを 1 件ずつ除外するために公開する。
     */
    public static boolean isValidToAddress(String address) {
        return address != null && EMAIL_PATTERN.matcher(address).matches();
    }
}
