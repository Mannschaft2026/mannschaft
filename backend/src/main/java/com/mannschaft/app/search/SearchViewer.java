package com.mannschaft.app.search;

import java.util.List;

/**
 * 検索の閲覧者。所属スコープとスコープ別ロールは search の集約側で 1 回だけ一括解決して渡す
 * （設計書 §6 検索実行フロー 3）。GUEST ロールしか持たないスコープは含めない。
 *
 * @param userId 閲覧者のユーザー ID
 * @param scopes 検索母集団に含める所属スコープ（スコープ単位のロール付き）
 */
public record SearchViewer(Long userId, List<ViewerScope> scopes) {

    /**
     * 閲覧者の所属スコープ 1 件。ロールはスコープ単位で判定する（全所属先の和集合で判定しない）。
     *
     * @param scopeType {@code TEAM} / {@code ORGANIZATION}
     * @param scopeId   スコープ ID
     * @param roleName  そのスコープでの実効ロール名（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER）
     */
    public record ViewerScope(String scopeType, Long scopeId, String roleName) {
    }
}
