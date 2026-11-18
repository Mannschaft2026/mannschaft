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

    /** スコープ種別: チーム。 */
    public static final String TEAM = "TEAM";

    /** スコープ種別: 組織。 */
    public static final String ORGANIZATION = "ORGANIZATION";

    public SearchViewer {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * 閲覧者の所属スコープ 1 件。ロールはスコープ単位で判定する（全所属先の和集合で判定しない）。
     *
     * @param scopeType {@code TEAM} / {@code ORGANIZATION}
     * @param scopeId   スコープ ID
     * @param roleName  そのスコープでの実効ロール名（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER）
     */
    public record ViewerScope(String scopeType, Long scopeId, String roleName) {
    }

    /** 母集団に含まれるチーム ID。 */
    public List<Long> teamIds() {
        return idsOf(TEAM);
    }

    /** 母集団に含まれる組織 ID。 */
    public List<Long> organizationIds() {
        return idsOf(ORGANIZATION);
    }

    /** 指定スコープが母集団に含まれるか。 */
    public boolean hasScope(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            return false;
        }
        return scopes.stream().anyMatch(s -> s.scopeType().equals(scopeType) && s.scopeId().equals(scopeId));
    }

    /**
     * 検索条件のスコープ指定で母集団を絞った閲覧者を返す（指定なしなら自身）。
     * 指定スコープが母集団に無ければ空の母集団になる。
     */
    public SearchViewer narrowTo(SearchCriteria criteria) {
        if (criteria == null || criteria.scopeType() == null) {
            return this;
        }
        return new SearchViewer(userId, scopes.stream()
                .filter(s -> s.scopeType().equals(criteria.scopeType()) && s.scopeId().equals(criteria.scopeId()))
                .toList());
    }

    private List<Long> idsOf(String scopeType) {
        return scopes.stream().filter(s -> s.scopeType().equals(scopeType)).map(ViewerScope::scopeId).toList();
    }
}
