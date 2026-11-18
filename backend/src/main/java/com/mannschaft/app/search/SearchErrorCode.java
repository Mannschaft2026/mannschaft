package com.mannschaft.app.search;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.6 グローバル検索のエラーコード定義。
 *
 * <p>HTTP ステータスは {@code Severity.WARN} 既定の 400。404 / 409 は
 * {@code GlobalExceptionHandler} の上書き表で指定する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum SearchErrorCode implements ErrorCode {

    /** 検索クエリが空（空白のみを含む） */
    EMPTY_QUERY("SEARCH_001", "検索クエリを入力してください", Severity.WARN),

    /** 検索クエリが長すぎる */
    QUERY_TOO_LONG("SEARCH_002", "検索クエリは100文字以内で入力してください", Severity.WARN),

    /** 保存済みクエリが見つからない（他人のものも同じ応答にする） */
    SAVED_QUERY_NOT_FOUND("SEARCH_003", "保存済み検索クエリが見つかりません", Severity.WARN),

    /** 検索履歴が見つからない（他人のものも同じ応答にする） */
    HISTORY_NOT_FOUND("SEARCH_004", "検索履歴が見つかりません", Severity.WARN),

    /** 保存済みクエリの上限超過（409） */
    MAX_SAVED_QUERIES_EXCEEDED("SEARCH_005", "保存済みクエリは最大10件です", Severity.WARN),

    /** 検索クエリが短すぎる */
    QUERY_TOO_SHORT("SEARCH_006", "検索クエリは2文字以上で入力してください", Severity.WARN),

    /** 検索種別が不正 */
    INVALID_TYPE("SEARCH_007", "検索種別の指定が正しくありません", Severity.WARN),

    /** スコープ指定が不正 */
    INVALID_SCOPE("SEARCH_008", "スコープの指定が正しくありません", Severity.WARN),

    /** ページ指定が不正 */
    INVALID_PAGE("SEARCH_009", "ページの指定が正しくありません", Severity.WARN),

    /** 日付・作成者の絞り込み指定が不正 */
    INVALID_FILTER("SEARCH_010", "絞り込み条件の指定が正しくありません", Severity.WARN),

    /** 保存する検索条件が JSON として不正 */
    INVALID_SAVED_QUERY_PARAMS("SEARCH_011", "保存する検索条件の形式が正しくありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
