package com.mannschaft.app.search;

import java.time.LocalDate;
import java.util.List;

/**
 * 入力検証・正規化を通った検索条件。
 *
 * @param query     正規化済みキーワード（trim・連続／全角スペースを半角 1 つへ畳み込み済み）
 * @param terms     スペース区切りの語（AND 条件）。演算子・ワイルドカードは未エスケープの生文字
 * @param scopeType スコープ絞り込み（{@code TEAM} / {@code ORGANIZATION}。未指定は null）
 * @param scopeId   スコープ ID（{@code scopeType} 指定時のみ非 null）
 * @param dateFrom  期間開始日（null 可。USER / TEAM / ORGANIZATION には適用しない）
 * @param dateTo    期間終了日（null 可。同上）
 * @param authorId  投稿者・作成者 ID（null 可。同上）
 */
public record SearchCriteria(
        String query,
        List<String> terms,
        String scopeType,
        Long scopeId,
        LocalDate dateFrom,
        LocalDate dateTo,
        Long authorId) {
}
