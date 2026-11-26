package com.mannschaft.app.supportaccess;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F10.9 サポート閲覧（本人承認制の代理ログイン）のエラーコード（SUPPORT_ACCESS_001〜013）。
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に登録する。
 * 旧 ADMIN_007/008（管理者変身）は本コードへ置き換えて廃番にする。</p>
 */
@Getter
@RequiredArgsConstructor
public enum SupportAccessErrorCode implements ErrorCode {

    /** grant が無い、または他の運営者・他人のもの（存在を明かさない）（404） */
    GRANT_NOT_FOUND("SUPPORT_ACCESS_001", "サポート閲覧の依頼が見つかりません", Severity.WARN),

    /** grant が無効（期限切れ・取消・未許可・閲覧未開始・本人の状態不可）（403） */
    GRANT_NOT_ACTIVE("SUPPORT_ACCESS_002", "サポート閲覧の許可が有効ではありません", Severity.WARN),

    /** 代理閲覧中は閲覧のみ。書込系メソッドは拒否する（403） */
    READ_ONLY_VIOLATION("SUPPORT_ACCESS_003", "サポート閲覧中は閲覧のみ可能です", Severity.WARN),

    /** 代理閲覧中は禁止の操作（認証・決済・書き出し・運営 API 等）（403） */
    OPERATION_FORBIDDEN("SUPPORT_ACCESS_004", "サポート閲覧中はこの操作を行えません", Severity.WARN),

    /** 対象が不正（運営アカウント・自分自身・退会手続中・状態不可）（422） */
    TARGET_INVALID("SUPPORT_ACCESS_005", "この利用者にはサポート閲覧を依頼できません", Severity.WARN),

    /** 同じ運営者×本人の依頼が既に開いている（409） */
    DUPLICATE_OPEN_GRANT("SUPPORT_ACCESS_006", "同じ利用者への依頼が既に開いています", Severity.WARN),

    /** 状態遷移が不正（409） */
    INVALID_TRANSITION("SUPPORT_ACCESS_007", "現在の状態ではこの操作を行えません", Severity.WARN),

    /** 理由が無い、または長さ違反（10〜1000字）（400） */
    REASON_INVALID("SUPPORT_ACCESS_008", "依頼理由は10〜1000文字で入力してください", Severity.WARN),

    /** 代理入力・後見ヘッダーとの併用（400） */
    PROXY_CONFLICT("SUPPORT_ACCESS_009", "代理入力・後見切替とサポート閲覧は併用できません", Severity.WARN),

    /** 運営アカウントがテナントに所属している（403） */
    OPERATOR_AFFILIATED("SUPPORT_ACCESS_010", "運営専用アカウントはチーム・組織等に所属できません", Severity.WARN),

    /** 依頼が多すぎる（運営者ごとに1日20件）（429） */
    REQUEST_RATE_LIMITED("SUPPORT_ACCESS_011", "本日の依頼上限に達しました", Severity.WARN),

    /** 廃止した旧・管理者変身ヘッダー（SupportAccessHeaders.LEGACY_IMPERSONATE）が送られた（400） */
    LEGACY_HEADER("SUPPORT_ACCESS_012", "このヘッダーは廃止されました。サポート閲覧の許可を依頼してください", Severity.WARN),

    /** 許可時の再認証（パスワード／パスキー）に失敗した、または材料が無い（400） */
    REAUTH_FAILED("SUPPORT_ACCESS_013", "本人確認（再認証）に失敗しました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
