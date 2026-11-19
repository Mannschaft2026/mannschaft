package com.mannschaft.app.proxy;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F14.1 代理入力・F08.9 後見切替のヘッダー検証（{@link ProxyInputContextFilter}）のエラーコード。
 *
 * <p>CMP-261113-1507: 従来は {@code {"error":"<日本語>"}} の独自形式で書いていたため利用者の言語へ訳せず、
 * FE の共通エラー処理（{@code error.code} / {@code error.message}）も読めなかった。共通の
 * {@code ErrorResponse} 形式と訳 properties に揃えるため ErrorCode 化した。HTTP ステータスはフィルタが
 * 書き込み時に明示する（下記の各定数の注記）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ProxyInputErrorCode implements ErrorCode {

    /** 400: 紙同意書経路の必須ヘッダー不足 */
    HEADERS_INCOMPLETE("PROXY_INPUT_001",
            "代理入力ヘッダーが不完全です。X-Proxy-Input-Source, X-Proxy-Original-Storage は必須です。", Severity.WARN),

    /** 400: 原本保管場所の転送形式・値が不正 */
    ORIGINAL_STORAGE_INVALID("PROXY_INPUT_002",
            "X-Proxy-Original-Storage の転送形式または原本保管場所が不正です。", Severity.WARN),

    /** 400: 代理対象ユーザー ID または同意書 ID が数値でない */
    IDS_INVALID("PROXY_INPUT_003",
            "X-Proxy-For-User-Id または X-Proxy-Consent-Id の形式が不正です。", Severity.WARN),

    /** 400: 入力元区分が不正 */
    INPUT_SOURCE_INVALID("PROXY_INPUT_004",
            "X-Proxy-Input-Source の値が不正です。PAPER_FORM / PHONE_INTERVIEW / IN_PERSON のいずれかを指定してください。",
            Severity.WARN),

    /** 401: 代理入力に認証が無い */
    AUTHENTICATION_REQUIRED("PROXY_INPUT_005", "代理入力を行うには認証が必要です。", Severity.WARN),

    /** 403: 有効な同意書が無い */
    CONSENT_NOT_FOUND("PROXY_INPUT_006",
            "有効な代理入力の同意書が見つかりません。同意書が承認済みかつ有効期限内であることを確認してください。",
            Severity.WARN),

    /** 403: 代理対象ユーザーと同意書の不一致 */
    SUBJECT_MISMATCH("PROXY_INPUT_007", "指定された代理対象ユーザーと同意書の内容が一致しません。", Severity.WARN),

    /** 400: 後見切替の子ユーザー ID が数値でない */
    SWITCH_TARGET_INVALID("PROXY_INPUT_008", "X-Proxy-For-User-Id の形式が不正です。", Severity.WARN),

    /** 401: 後見切替に認証が無い */
    SWITCH_AUTHENTICATION_REQUIRED("PROXY_INPUT_009", "後見切替を行うには認証が必要です。", Severity.WARN),

    /** 403: 有効な保護者リンクが無い */
    SWITCH_LINK_NOT_FOUND("PROXY_INPUT_010", "有効な保護者リンクが見つからないため後見切替できません。", Severity.WARN),

    /** 403: 年齢到達で後見切替が封印された */
    SWITCH_AGE_LOCKED("PROXY_INPUT_011", "このお子さまは年齢到達のため後見切替できません。", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
