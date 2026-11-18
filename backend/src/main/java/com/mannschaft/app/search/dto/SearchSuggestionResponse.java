package com.mannschaft.app.search.dto;

import java.util.List;

/**
 * 検索サジェストレスポンス（{@code GET /api/v1/search/suggestions}、設計書 §4）。
 *
 * @param suggestions 候補（KEYWORD は本人の履歴のみ。USER / TEAM / ORGANIZATION は検索本体と同じ可視性述語を通したもの）
 */
public record SearchSuggestionResponse(List<Item> suggestions) {

    /**
     * 候補 1 件。種別に関係しない ID は null。
     *
     * @param text           表示文字列
     * @param type           {@code KEYWORD} / {@code USER} / {@code TEAM} / {@code ORGANIZATION}
     * @param userId         USER の ID
     * @param teamId         TEAM の ID
     * @param organizationId ORGANIZATION の ID
     * @param url            遷移先（無ければ null）
     */
    public record Item(String text, String type, Long userId, Long teamId, Long organizationId, String url) {

        /** 本人の検索履歴から作るキーワード候補。 */
        public static Item keyword(String text) {
            return new Item(text, "KEYWORD", null, null, null, null);
        }
    }
}
