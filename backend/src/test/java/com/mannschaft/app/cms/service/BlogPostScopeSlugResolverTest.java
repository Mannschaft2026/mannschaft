package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BlogPostResponse.BlogPostScopeDto;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostScopeSlugResolver} の単体テスト。一括取得（N+1 回避）と付与の対応を確かめる。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostScopeSlugResolver 単体テスト")
class BlogPostScopeSlugResolverTest {

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private BlogPostScopeSlugResolver resolver;

    private static BlogPostResponse post(long id, Long teamId, Long organizationId) {
        return BlogPostResponse.builder()
                .id(id)
                .scope(new BlogPostScopeDto(teamId, organizationId, 99L, 99L))
                .build();
    }

    @Test
    @DisplayName("一覧: チーム・組織それぞれ1回の一括取得で、各記事に自分のスコープの slug を付与する")
    void 一覧は種別ごとに1回だけ一括取得する() {
        given(teamService.getSlugsByIds(Set.of(1L, 2L))).willReturn(Map.of(1L, "team-a", 2L, "team-b"));
        given(organizationService.getSlugsByIds(Set.of(10L))).willReturn(Map.of(10L, "org-x"));

        List<BlogPostResponse> result = resolver.attachAll(List.of(
                post(100L, 1L, null), post(101L, 2L, null), post(102L, 1L, null),
                post(103L, null, 10L), post(104L, null, null)));

        assertThat(result).extracting(BlogPostResponse::getId).containsExactly(100L, 101L, 102L, 103L, 104L);
        assertThat(result).extracting(p -> p.getScope().teamSlug())
                .containsExactly("team-a", "team-b", "team-a", null, null);
        assertThat(result).extracting(p -> p.getScope().organizationSlug())
                .containsExactly(null, null, null, "org-x", null);
        verify(teamService, times(1)).getSlugsByIds(any());
        verify(organizationService, times(1)).getSlugsByIds(any());
    }

    @Test
    @DisplayName("個人記事だけなら他ドメインを呼ばず、slug は null")
    void 個人記事のみは他ドメインを呼ばない() {
        BlogPostResponse result = resolver.attach(post(100L, null, null));

        assertThat(result.getScope().teamSlug()).isNull();
        assertThat(result.getScope().organizationSlug()).isNull();
        assertThat(result.getScope().userId()).isEqualTo(99L);
        verify(teamService, never()).getSlugsByIds(any());
        verify(organizationService, never()).getSlugsByIds(any());
    }

    @Test
    @DisplayName("slug を解決できない（論理削除済みなど）チームは null のまま返し、ID は保つ")
    void 解決できないslugはnull() {
        given(teamService.getSlugsByIds(Set.of(1L))).willReturn(Map.of());

        BlogPostResponse result = resolver.attach(post(100L, 1L, null));

        assertThat(result.getScope().teamId()).isEqualTo(1L);
        assertThat(result.getScope().teamSlug()).isNull();
    }
}
