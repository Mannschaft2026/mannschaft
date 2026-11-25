package com.mannschaft.app.team.service;

import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.common.visibility.mapping.TeamVisibilityMapper;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchScopeVisibilityPredicate;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamKeywordSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索 TEAM 種別の {@link SearchContributor}（F04.6 §6、CMP-261116-0722）。
 *
 * <p>可視性: チームの公開範囲の段を満たす（チーム詳細の正準 {@code ContentVisibilityChecker} と同じ規則。
 * PUBLIC は誰でも、GUESTS_AND_ABOVE は所属者、SUPPORTERS_AND_ABOVE は SUPPORTER 以上、MEMBERS_AND_ABOVE は
 * MEMBER 以上。ロールは閲覧者のそのチームでのロール）かつ ACTIVE かつ未アーカイブ かつ未削除。件数と取得は
 * 同一の {@link SearchScopeVisibilityPredicate} から {@link TeamKeywordSearchRepository} の同一 WHERE 句で出す。</p>
 *
 * <p>スコープ絞り込み: {@code scopeType=TEAM} はそのチーム自身だけ（所属は集約側で確認済み）、
 * {@code scopeType=ORGANIZATION} は該当なし（組織スコープに属するチームの列挙は本種別では扱わない）。</p>
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamSearchContributor implements SearchContributor {

    private final TeamKeywordSearchRepository teamKeywordSearchRepository;

    @Override
    public SearchType type() {
        return SearchType.TEAM;
    }

    @Override
    public long count(SearchCriteria criteria, SearchViewer viewer) {
        if (excludedByScope(criteria)) {
            return 0L;
        }
        return teamKeywordSearchRepository.count(criteria.terms(), visibility(viewer), onlyTeamId(criteria));
    }

    @Override
    public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
        if (excludedByScope(criteria)) {
            return List.of();
        }
        return teamKeywordSearchRepository
                .find(criteria.terms(), visibility(viewer), onlyTeamId(criteria), offset, limit)
                .stream()
                .map(team -> toHit(team, criteria.terms()))
                .toList();
    }

    private static SearchScopeVisibilityPredicate<TeamEntity.Visibility> visibility(SearchViewer viewer) {
        return SearchScopeVisibilityPredicate.of(
                TeamEntity.Visibility.class, TeamVisibilityMapper::toStandard, viewer, SearchViewer.TEAM);
    }

    private static boolean excludedByScope(SearchCriteria criteria) {
        return criteria.scopeType() != null && !SearchViewer.TEAM.equals(criteria.scopeType());
    }

    private static Long onlyTeamId(SearchCriteria criteria) {
        return SearchViewer.TEAM.equals(criteria.scopeType()) ? criteria.scopeId() : null;
    }

    private SearchHit toHit(TeamEntity team, List<String> terms) {
        Map<String, List<Integer>> highlights = new HashMap<>();
        List<Integer> range = SearchTextHighlighter.titleRange(team.getName(), terms);
        if (range != null) {
            highlights.put("title", range);
        }
        return new SearchHit(SearchType.TEAM, team.getId(), team.getName(), null, highlights, null, null, null,
                SearchHit.createdAtOf(team.getCreatedAt()),
                "/teams/" + team.getSlug(), List.of());
    }
}
