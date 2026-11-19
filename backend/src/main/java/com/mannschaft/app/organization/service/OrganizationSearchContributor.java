package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationKeywordSearchRepository;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索 ORGANIZATION 種別の {@link SearchContributor}（F04.6 §6、CMP-261116-0722）。
 *
 * <p>可視性: (PUBLIC または本人所属) かつ ACTIVE かつ未アーカイブ かつ未削除。件数と取得は
 * {@link OrganizationKeywordSearchRepository} の同一 WHERE 句から出す。</p>
 *
 * <p>スコープ絞り込み: {@code scopeType=ORGANIZATION} はその組織自身だけ（所属は集約側で確認済み）、
 * {@code scopeType=TEAM} は該当なし。</p>
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationSearchContributor implements SearchContributor {

    private final OrganizationKeywordSearchRepository organizationKeywordSearchRepository;
    private final Clock clock;

    @Override
    public SearchType type() {
        return SearchType.ORGANIZATION;
    }

    @Override
    public long count(SearchCriteria criteria, SearchViewer viewer) {
        if (excludedByScope(criteria)) {
            return 0L;
        }
        return organizationKeywordSearchRepository.count(
                criteria.terms(), viewer.organizationIds(), onlyOrgId(criteria));
    }

    @Override
    public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
        if (excludedByScope(criteria)) {
            return List.of();
        }
        return organizationKeywordSearchRepository
                .find(criteria.terms(), viewer.organizationIds(), onlyOrgId(criteria), offset, limit)
                .stream()
                .map(org -> toHit(org, criteria.terms()))
                .toList();
    }

    private static boolean excludedByScope(SearchCriteria criteria) {
        return criteria.scopeType() != null && !SearchViewer.ORGANIZATION.equals(criteria.scopeType());
    }

    private static Long onlyOrgId(SearchCriteria criteria) {
        return SearchViewer.ORGANIZATION.equals(criteria.scopeType()) ? criteria.scopeId() : null;
    }

    private SearchHit toHit(OrganizationEntity org, List<String> terms) {
        Map<String, List<Integer>> highlights = new HashMap<>();
        List<Integer> range = SearchTextHighlighter.titleRange(org.getName(), terms);
        if (range != null) {
            highlights.put("title", range);
        }
        return new SearchHit(SearchType.ORGANIZATION, org.getId(), org.getName(), null, highlights, null, null,
                null, org.getCreatedAt() == null ? null : org.getCreatedAt().atZone(clock.getZone()).toOffsetDateTime(),
                "/organizations/" + org.getSlug(), List.of());
    }
}
