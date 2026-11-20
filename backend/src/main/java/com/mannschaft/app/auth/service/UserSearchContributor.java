package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserKeywordSearchRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.social.service.SocialProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索 USER 種別の {@link SearchContributor}（F04.6 §6、CMP-261116-0722）。
 *
 * <p>可視性: {@code is_searchable = TRUE}・ACTIVE・未削除で、閲覧者と<b>同一スコープ</b>に在籍する利用者のみ
 * （{@link MembershipService#getActiveUserIdsInScopes}）。閲覧者の母集団は GUEST のみ・論理削除済み等の
 * スコープを除いたもの（集約側で解決済み）。{@code scopeType} 指定時はそのスコープだけを共有の判定に使う。
 * 退会者（匿名化で {@code is_searchable = FALSE}・論理削除）は出ない。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 共有スコープの解決で membership ドメインの Service を
 * 呼ぶため、auth ドメインのトランザクションを他ドメインへ広げない（D-3 / D-3T 番人）。読み取りは各
 * Repository 呼び出しの単位で完結する。</p>
 */
@Component
@RequiredArgsConstructor
public class UserSearchContributor implements SearchContributor {

    /** {@code IN ()} を避けるためのダミー ID（どの行にも一致しない）。 */
    private static final List<Long> NO_MATCH = List.of(-1L);

    private final UserKeywordSearchRepository userKeywordSearchRepository;
    private final MembershipService membershipService;
    private final SocialProfileService socialProfileService;

    @Override
    public SearchType type() {
        return SearchType.USER;
    }

    @Override
    public long count(SearchCriteria criteria, SearchViewer viewer) {
        List<Long> candidates = candidates(criteria, viewer);
        if (candidates.isEmpty()) {
            return 0L;
        }
        return userKeywordSearchRepository.count(criteria.terms(), candidates);
    }

    @Override
    public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
        List<Long> candidates = candidates(criteria, viewer);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UserEntity> users = userKeywordSearchRepository.find(criteria.terms(), candidates, offset, limit);
        // ハンドルは結果件数に依らず 1 SQL で一括解決する（0 件でも同じ本数にし、発行 SQL 数を
        // 結果の有無や所属スコープ数で変えない。AC-C18）。
        List<Long> userIds = users.stream().map(UserEntity::getId).toList();
        Map<Long, String> handles = socialProfileService.findActiveHandlesByUserIds(
                userIds.isEmpty() ? NO_MATCH : userIds);
        return users.stream().map(u -> toHit(u, handles.get(u.getId()), criteria.terms())).toList();
    }

    /** 閲覧者と同一スコープに在籍する利用者 ID（スコープ指定時はそのスコープのみ）。 */
    private List<Long> candidates(SearchCriteria criteria, SearchViewer viewer) {
        SearchViewer scoped = viewer.narrowTo(criteria);
        List<Long> teamIds = scoped.teamIds();
        List<Long> orgIds = scoped.organizationIds();
        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            return List.of();
        }
        return membershipService.getActiveUserIdsInScopes(
                teamIds.isEmpty() ? NO_MATCH : teamIds,
                orgIds.isEmpty() ? NO_MATCH : orgIds);
    }

    private static SearchHit toHit(UserEntity user, String handle, List<String> terms) {
        Map<String, List<Integer>> highlights = new HashMap<>();
        List<Integer> range = SearchTextHighlighter.titleRange(user.getDisplayName(), terms);
        if (range != null) {
            highlights.put("title", range);
        }
        String snippet = user.getNickname2() == null ? null
                : SearchTextHighlighter.snippet(user.getNickname2(), terms);
        String url = handle == null ? null : "/social/" + handle;
        return new SearchHit(SearchType.USER, user.getId(), user.getDisplayName(), snippet, highlights, null,
                null, null, null, url, List.of("SEND_DM"));
    }
}
