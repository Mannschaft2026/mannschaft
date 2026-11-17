package com.mannschaft.app.search;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 検索結果 1 件（設計書 §4 レスポンス {@code results[]}）。
 *
 * @param type       種別
 * @param id         対象 ID
 * @param title      プレーンテキストの題名（{@code <mark>} なし。題名を持たない種別は null）
 * @param snippet    HTML エスケープ後に一致箇所のみ {@code <mark>} で囲んだ抜粋（最大 200 文字）
 * @param highlights 一致位置（例 {@code title -> [start, end]}、プレーンテキスト基準）
 * @param relevance  FULLTEXT 種別の関連度（LIKE 種別は null）
 * @param author     作成者（無い種別は null）
 * @param scope      所属スコープ（無い種別は null）
 * @param createdAt  作成日時
 * @param url        遷移先の実在 FE ルート（リンク不可は null）
 * @param actions    実行可能アクション
 */
public record SearchHit(
        SearchType type,
        Long id,
        String title,
        String snippet,
        Map<String, List<Integer>> highlights,
        Double relevance,
        Author author,
        ScopeRef scope,
        LocalDateTime createdAt,
        String url,
        List<String> actions) {

    /** 作成者。 */
    public record Author(Long id, String displayName, String avatarUrl) {
    }

    /** 所属スコープ。 */
    public record ScopeRef(String type, Long id, String name) {
    }
}
