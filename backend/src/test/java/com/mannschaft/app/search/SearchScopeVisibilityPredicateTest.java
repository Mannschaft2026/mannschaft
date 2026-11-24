package com.mannschaft.app.search;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.mapping.OrganizationVisibilityMapper;
import com.mannschaft.app.common.visibility.mapping.TeamVisibilityMapper;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SearchScopeVisibilityPredicate} の単体テスト（CMP-261116-0722 アリシゼーション指摘の回帰固定）。
 *
 * <p>チーム・組織の visibility 全値について、閲覧者のスコープ別ロールから出す ID が正準
 * （{@code AbstractContentVisibilityResolver#visibleByLevel} の閾値）と一致すること、
 * 件数と取得で同じ WHERE 断片を作ることを固定する。</p>
 */
@DisplayName("SearchScopeVisibilityPredicate: スコープ本体の公開範囲の段")
class SearchScopeVisibilityPredicateTest {

    private static final long SUPPORTED = 10L;
    private static final long MEMBERED = 20L;
    private static final long ADMINED = 30L;

    private static SearchViewer viewer() {
        return new SearchViewer(1L, List.of(
                new SearchViewer.ViewerScope(SearchViewer.TEAM, SUPPORTED, "SUPPORTER"),
                new SearchViewer.ViewerScope(SearchViewer.TEAM, MEMBERED, "MEMBER"),
                new SearchViewer.ViewerScope(SearchViewer.TEAM, ADMINED, "ADMIN"),
                new SearchViewer.ViewerScope(SearchViewer.ORGANIZATION, SUPPORTED, "SUPPORTER")));
    }

    private static Map<String, Object> params(SearchScopeVisibilityPredicate<?> predicate, StringBuilder sb) {
        Map<String, Object> params = new HashMap<>();
        predicate.appendTo(sb, "t", params);
        return params;
    }

    @Test
    @DisplayName("TEAM: 全値を網羅し、MEMBERS_AND_ABOVE はサポーターのチームを含めず、PUBLIC は ID 条件なし")
    void TEAM_全値() {
        SearchScopeVisibilityPredicate<TeamEntity.Visibility> predicate = SearchScopeVisibilityPredicate.of(
                TeamEntity.Visibility.class, TeamVisibilityMapper::toStandard, viewer(), SearchViewer.TEAM);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> params = params(predicate, sb);

        assertThat(params.get("publicVisibilities")).isEqualTo(List.of(TeamEntity.Visibility.PUBLIC));
        Map<Object, Object> idsByValue = new HashMap<>();
        for (int i = 0; params.containsKey("scopeVisibility" + i); i++) {
            idsByValue.put(params.get("scopeVisibility" + i), params.get("scopeVisibleIds" + i));
        }
        assertThat(idsByValue).containsOnlyKeys(TeamEntity.Visibility.GUESTS_AND_ABOVE,
                TeamEntity.Visibility.SUPPORTERS_AND_ABOVE, TeamEntity.Visibility.MEMBERS_AND_ABOVE);
        assertThat(idsByValue.get(TeamEntity.Visibility.GUESTS_AND_ABOVE))
                .isEqualTo(List.of(SUPPORTED, MEMBERED, ADMINED));
        assertThat(idsByValue.get(TeamEntity.Visibility.SUPPORTERS_AND_ABOVE))
                .isEqualTo(List.of(SUPPORTED, MEMBERED, ADMINED));
        assertThat(idsByValue.get(TeamEntity.Visibility.MEMBERS_AND_ABOVE))
                .isEqualTo(List.of(MEMBERED, ADMINED));
        assertThat(sb.toString()).startsWith(" AND (t.visibility IN :publicVisibilities OR (t.visibility = :");
    }

    @Test
    @DisplayName("ORGANIZATION: PRIVATE は所属者（サポーター含む）、PUBLIC は ID 条件なし")
    void ORGANIZATION_全値() {
        SearchScopeVisibilityPredicate<OrganizationEntity.Visibility> predicate = SearchScopeVisibilityPredicate.of(
                OrganizationEntity.Visibility.class, OrganizationVisibilityMapper::toStandard, viewer(),
                SearchViewer.ORGANIZATION);
        Map<String, Object> params = params(predicate, new StringBuilder());

        assertThat(params.get("publicVisibilities")).isEqualTo(List.of(OrganizationEntity.Visibility.PUBLIC));
        assertThat(params.get("scopeVisibility0")).isEqualTo(OrganizationEntity.Visibility.PRIVATE);
        assertThat(params.get("scopeVisibleIds0")).isEqualTo(List.of(SUPPORTED));
        assertThat(params).doesNotContainKey("scopeVisibility1");
    }

    @Test
    @DisplayName("所属がなければ公開値だけの条件になる（非公開の段は誰にも開かない）")
    void 所属なしは公開のみ() {
        SearchScopeVisibilityPredicate<TeamEntity.Visibility> predicate = SearchScopeVisibilityPredicate.of(
                TeamEntity.Visibility.class, TeamVisibilityMapper::toStandard,
                new SearchViewer(1L, List.of()), SearchViewer.TEAM);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> params = params(predicate, sb);

        assertThat(sb.toString()).isEqualTo(" AND (t.visibility IN :publicVisibilities)");
        assertThat(params).containsOnlyKeys("publicVisibilities");
    }

    @Test
    @DisplayName("段の閾値は正準 visibleByLevel と同じ。スコープ本体の段でない値は fail-closed（null）")
    void 段の閾値() {
        assertThat(SearchScopeVisibilityPredicate.minimumRole(StandardVisibility.SCOPE_AFFILIATED))
                .isEqualTo("GUEST");
        assertThat(SearchScopeVisibilityPredicate.minimumRole(StandardVisibility.SUPPORTERS_AND_ABOVE))
                .isEqualTo("SUPPORTER");
        assertThat(SearchScopeVisibilityPredicate.minimumRole(StandardVisibility.MEMBERS_AND_ABOVE))
                .isEqualTo("MEMBER");
        assertThat(SearchScopeVisibilityPredicate.minimumRole(StandardVisibility.DEPUTY_ADMINS_AND_ABOVE))
                .isEqualTo("DEPUTY_ADMIN");
        assertThat(SearchScopeVisibilityPredicate.minimumRole(StandardVisibility.ADMINS_AND_ABOVE))
                .isEqualTo("ADMIN");
        for (StandardVisibility v : List.of(StandardVisibility.PRIVATE, StandardVisibility.FOLLOWERS_ONLY,
                StandardVisibility.CUSTOM_TEMPLATE, StandardVisibility.ORGANIZATION_WIDE,
                StandardVisibility.ORGANIZATION_AND_DESCENDANTS)) {
            assertThat(SearchScopeVisibilityPredicate.minimumRole(v)).as("%s", v).isNull();
        }
    }
}
