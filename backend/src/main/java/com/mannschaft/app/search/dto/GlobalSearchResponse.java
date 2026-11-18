package com.mannschaft.app.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchType;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/search} の応答本体（設計書 §4）。{@code { "data": {...}, "meta": {...} }}。
 *
 * @param data 検索結果
 * @param meta ビュー種別とページ情報
 */
public record GlobalSearchResponse(Data data, Meta meta) {

    /**
     * @param results         結果（概要ビューは種別ごと最大 5 件の混在、詳細ビューは 1 ページ分）
     * @param typeCounts      9 種別すべてのキーを持つ真の可視件数
     * @param query           正規化済みキーワード
     * @param timedOutTypes   例外・タイムアウトでスキップした種別
     * @param zeroResultsHelp 全種別 0 件のときのみ非 null
     */
    @Schema(name = "GlobalSearchData")
    public record Data(
            List<SearchHit> results,
            Map<SearchType, Long> typeCounts,
            String query,
            List<SearchType> timedOutTypes,
            ZeroResultsHelp zeroResultsHelp) {
    }

    /**
     * @param didYouMean   本人の検索履歴のみから出す候補（無ければ null）
     * @param broaderQuery 語数を 1 つ減らした候補（単一語は null）
     */
    @Schema(name = "GlobalSearchZeroResultsHelp")
    public record ZeroResultsHelp(String didYouMean, String broaderQuery) {
    }

    /**
     * @param viewMode   {@code OVERVIEW} / {@code DETAIL}
     * @param total      詳細ビューの総件数（{@code typeCounts[type]} と一致）
     * @param page       0 始まりのページ
     * @param size       ページサイズ
     * @param totalPages 総ページ数
     */
    @Schema(name = "GlobalSearchMeta")
    public record Meta(ViewMode viewMode, long total, int page, int size, int totalPages) {
    }

    /** ビュー種別。 */
    @Schema(name = "GlobalSearchViewMode")
    public enum ViewMode {
        /** type 未指定・複数指定。 */
        OVERVIEW,
        /** type 単一指定。 */
        DETAIL
    }
}
