package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostScopeDto;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ブログ記事応答のスコープにチーム・組織の slug を付与する。
 *
 * <p>チーム・組織ページの URL は slug が前提で、数値 ID の URL は 404 になる。FE が「マイチーム」一覧から slug を
 * 引くと、所属行の無い管理者（SYSTEM_ADMIN・親組織の管理者）やアーカイブ済み・一覧の件数上限を超えるチームで
 * 引けないため、記事応答そのものに記事のスコープの slug を載せる（閲覧者の所属とは無関係）。</p>
 *
 * <p>他ドメインの slug は {@link TeamService#getSlugsByIds} / {@link OrganizationService#getSlugsByIds} 経由で
 * 種別ごとに1回だけ一括取得する（N+1 回避・他ドメインの Repository は参照しない）。
 * 本クラスは {@code @Transactional} を持たず、Controller から {@link BlogPostService} の取引の外で呼ぶ
 * （ドメイン境界原則5・D-3T: cms の取引の中から他ドメインを呼ばない）。論理削除済みのチーム・組織は
 * 通常の slug 解決に倣って null のまま返す。</p>
 */
@Component
@RequiredArgsConstructor
public class BlogPostScopeSlugResolver {

    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 1件の記事応答にスコープの slug を付与する。
     *
     * @param post 記事応答（null・スコープ無しはそのまま返す）
     * @return slug を付与した記事応答
     */
    public BlogPostResponse attach(BlogPostResponse post) {
        if (post == null) {
            return null;
        }
        return attachAll(List.of(post)).get(0);
    }

    /**
     * 複数の記事応答にスコープの slug を付与する。チーム・組織それぞれ1回の一括取得で解決する。
     *
     * @param posts 記事応答の一覧
     * @return slug を付与した記事応答の一覧（順序は入力と同じ）
     */
    public List<BlogPostResponse> attachAll(List<BlogPostResponse> posts) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }
        Set<Long> teamIds = new HashSet<>();
        Set<Long> organizationIds = new HashSet<>();
        for (BlogPostResponse post : posts) {
            BlogPostScopeDto scope = post.getScope();
            if (scope == null) {
                continue;
            }
            if (scope.teamId() != null) {
                teamIds.add(scope.teamId());
            }
            if (scope.organizationId() != null) {
                organizationIds.add(scope.organizationId());
            }
        }
        Map<Long, String> teamSlugs = teamIds.isEmpty() ? Map.of() : teamService.getSlugsByIds(teamIds);
        Map<Long, String> organizationSlugs =
                organizationIds.isEmpty() ? Map.of() : organizationService.getSlugsByIds(organizationIds);
        return posts.stream()
                .map(post -> {
                    BlogPostScopeDto scope = post.getScope();
                    if (scope == null) {
                        return post;
                    }
                    String teamSlug = scope.teamId() != null ? teamSlugs.get(scope.teamId()) : null;
                    String organizationSlug =
                            scope.organizationId() != null ? organizationSlugs.get(scope.organizationId()) : null;
                    return post.withScope(scope.withSlugs(teamSlug, organizationSlug));
                })
                .toList();
    }
}
