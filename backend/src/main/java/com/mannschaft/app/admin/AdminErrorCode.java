package com.mannschaft.app.admin;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F10.1 管理基盤機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

    /** フィーチャーフラグが見つからない */
    FEATURE_FLAG_NOT_FOUND("ADMIN_001", "フィーチャーフラグが見つかりません", Severity.WARN),

    /** フラグキーが重複 */
    FEATURE_FLAG_KEY_DUPLICATE("ADMIN_002", "同一のフラグキーが既に存在します", Severity.WARN),

    /** メンテナンススケジュールが見つからない */
    MAINTENANCE_NOT_FOUND("ADMIN_003", "メンテナンススケジュールが見つかりません", Severity.WARN),

    /** メンテナンスステータス不正 */
    INVALID_MAINTENANCE_STATUS("ADMIN_004", "この操作は現在のステータスでは実行できません", Severity.WARN),

    /** メンテナンス期間不正 */
    INVALID_MAINTENANCE_PERIOD("ADMIN_005", "開始日時は終了日時より前に設定してください", Severity.WARN),

    /** バッチジョブログが見つからない */
    BATCH_JOB_LOG_NOT_FOUND("ADMIN_006", "バッチジョブログが見つかりません", Severity.WARN),

    /** 管理者変身ヘッダーをシステム管理者以外が指定した（AdminImpersonationFilter・403） */
    IMPERSONATION_SYSTEM_ADMIN_ONLY("ADMIN_007", "変身機能はシステム管理者のみ使用できます", Severity.WARN),

    /** 管理者変身ヘッダーの値が数値でない（AdminImpersonationFilter・400） */
    IMPERSONATION_TARGET_INVALID("ADMIN_008", "X-Admin-Impersonate-User-Id の形式が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
