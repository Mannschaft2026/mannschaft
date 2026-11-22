package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * N9: 元コンテンツが公開状態でないお知らせは、チームのお知らせ一覧に出ない（タイトル・抜粋のキャッシュを漏らさない）。
 *
 * <p>公開→下書きに戻した記事のお知らせも一覧から消える（元の状態に追従）。実 MySQL・Controller 経由で検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("N9 元コンテンツが非公開のお知らせは一覧に出ない（IT）")
class AnnouncementSourceStatusFeedIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnnouncementFeedRepository feedRepository;
    @PersistenceContext private EntityManager em;

    private Long teamId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (:n, 'PUBLIC', 1, 0, 0, :s, NOW(), NOW())")
                .setParameter("n", "N9 チーム " + key).setParameter("s", "n9-" + key).executeUpdate();
        teamId = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :n")
                .setParameter("n", "N9 チーム " + key).getSingleResult()).longValue();
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:e, 'N9', 'テスト', 'N9 テスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', "
                        + "'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("e", "n9-" + key + "@example.com").executeUpdate();
        memberId = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", "n9-" + key + "@example.com").getSingleResult()).longValue();
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberId.toString(), null, List.of()));
    }

    private BlogPostEntity blog(PostStatus status) {
        BlogPostEntity post = BlogPostEntity.builder().teamId(teamId).authorId(memberId).title("N9 記事")
                .slug("n9-" + UUID.randomUUID()).body("本文").visibility(Visibility.MEMBERS_ONLY)
                .postType(PostType.BLOG).status(status)
                .publishedAt(status == PostStatus.PUBLISHED ? LocalDateTime.now().minusDays(1) : null).build();
        em.persist(post);
        return post;
    }

    private TimelinePostEntity timeline(com.mannschaft.app.timeline.PostStatus status) {
        TimelinePostEntity post = TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(teamId)
                .userId(memberId).content("N9 投稿").status(status).build();
        em.persist(post);
        return post;
    }

    private Long feed(AnnouncementSourceType type, Long sourceId) {
        AnnouncementFeedEntity feed = feedRepository.saveAndFlush(AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.TEAM).scopeId(teamId).sourceType(type).sourceId(sourceId)
                .authorId(memberId).titleCache("N9 タイトル").excerptCache("N9 抜粋")
                .visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE).build());
        return feed.getId();
    }

    private List<Long> listedIds() throws Exception {
        em.flush();
        em.clear();
        String body = mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamId))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(body).path("data");
        List<Long> ids = new ArrayList<>();
        data.forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    @Test
    @DisplayName("公開済みブログのお知らせは一覧に出る（従来どおり）")
    void publishedBlogListed() throws Exception {
        Long id = feed(AnnouncementSourceType.BLOG_POST, blog(PostStatus.PUBLISHED).getId());
        assertThat(listedIds()).as("公開済みブログ由来のお知らせは出る").contains(id);
    }

    @Test
    @DisplayName("下書きブログのお知らせは一覧に出ない（タイトル・抜粋を漏らさない）")
    void draftBlogNotListed() throws Exception {
        Long id = feed(AnnouncementSourceType.BLOG_POST, blog(PostStatus.DRAFT).getId());
        assertThat(listedIds()).as("下書きブログ由来のお知らせは一覧に出ない").doesNotContain(id);
    }

    @Test
    @DisplayName("公開→下書きに戻したブログのお知らせは一覧から消え、再公開で戻る")
    void unpublishedBlogDisappearsAndReturns() throws Exception {
        BlogPostEntity post = blog(PostStatus.PUBLISHED);
        Long id = feed(AnnouncementSourceType.BLOG_POST, post.getId());
        assertThat(listedIds()).contains(id);

        post = em.find(BlogPostEntity.class, post.getId());
        post.unpublish(LocalDateTime.now());
        assertThat(listedIds()).as("下書きに戻したら一覧から消える").doesNotContain(id);

        post = em.find(BlogPostEntity.class, post.getId());
        post.completeScheduledPublish();
        assertThat(listedIds()).as("再公開したら一覧に戻る").contains(id);
    }

    @Test
    @DisplayName("タイムライン: 公開中は出る・下書き/予約/非表示は出ない")
    void timelineStatuses() throws Exception {
        Long published = feed(AnnouncementSourceType.TIMELINE_POST,
                timeline(com.mannschaft.app.timeline.PostStatus.PUBLISHED).getId());
        Long draft = feed(AnnouncementSourceType.TIMELINE_POST,
                timeline(com.mannschaft.app.timeline.PostStatus.DRAFT).getId());
        Long hidden = feed(AnnouncementSourceType.TIMELINE_POST,
                timeline(com.mannschaft.app.timeline.PostStatus.HIDDEN).getId());
        List<Long> ids = listedIds();
        assertThat(ids).contains(published).doesNotContain(draft, hidden);
    }
}
