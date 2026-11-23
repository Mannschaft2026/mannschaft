package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.inbox.service.adapter.AnnouncementInboxAdapter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.announcement.visibility.AnnouncementFeedVisibilityResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * N9: 元コンテンツ（ブログ記事・タイムライン投稿）が公開済みでないお知らせの扱い（実 MySQL・Controller 経由）。
 *
 * <ul>
 *   <li><b>お知らせ化</b>（POST）: 下書き・予約公開・論理削除・他人の下書きは、不在の sourceId と同一応答
 *       （404 {@code ANNOUNCE_006}）。公開済みは 201。</li>
 *   <li><b>ID 指定の経路</b>（ピン留め・単件既読・read-batch・インボックスの可視判定・F00 Resolver）: 元が公開済みでない
 *       （下書き・論理削除）お知らせは、不在のお知らせ ID と同一応答（404 {@code ANNOUNCE_001}）・不可視。
 *       ピン留めの応答はタイトル・抜粋を含むため、ADMIN でも返さない。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("N9 元コンテンツが公開済みでないお知らせ: お知らせ化・ID 指定経路は不在と同一（IT）")
class AnnouncementSourcePublicationIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しない ID。 */
    private static final long MISSING_ID = Long.MAX_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnnouncementFeedRepository feedRepository;
    @Autowired private AnnouncementInboxAdapter inboxAdapter;
    @Autowired private AnnouncementFeedVisibilityResolver visibilityResolver;
    @PersistenceContext private EntityManager em;

    private String key;
    private Long teamId;
    private Long otherTeamId;
    private Long orgId;
    /** teamId・orgId の ADMIN。 */
    private Long adminId;
    /** teamId・orgId のメンバー。 */
    private Long memberId;
    /** 他チームのメンバー（他人の下書きの著者）。 */
    private Long otherId;

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        teamId = insertTeam("a");
        otherTeamId = insertTeam("b");
        orgId = insertOrganization();
        adminId = insertUser("admin");
        memberId = insertUser("member");
        otherId = insertUser("other");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", null, orgId);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        em.flush();
    }

    // ═════════════════════════════════════════════════════════════════════
    // お知らせ化（POST）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST お知らせ化: 元が公開済みでなければ不在の sourceId と同一応答")
    class Create {

        @Test
        @DisplayName("公開済みブログは 201 で作られる")
        void publishedBlog_201() throws Exception {
            Long postId = blog(teamId, null, adminId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1)).getId();
            MvcResult result = perform(adminId, create("BLOG_POST", postId));
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(feedCount(AnnouncementSourceType.BLOG_POST, postId)).isEqualTo(1);
        }

        @Test
        @DisplayName("下書きブログ → 不在と同一（404 ANNOUNCE_006）・行を作らない")
        void draftBlog_sameAsMissing() throws Exception {
            Long postId = blog(teamId, null, adminId, PostStatus.DRAFT, null).getId();
            assertCreateSameAsMissing("BLOG_POST", postId);
        }

        @Test
        @DisplayName("予約公開ブログ（DRAFT＋未来の公開日時）→ 不在と同一")
        void scheduledBlog_sameAsMissing() throws Exception {
            Long postId = blog(teamId, null, adminId, PostStatus.DRAFT, LocalDateTime.now().plusDays(3)).getId();
            assertCreateSameAsMissing("BLOG_POST", postId);
        }

        @Test
        @DisplayName("論理削除したブログ → 不在と同一")
        void deletedBlog_sameAsMissing() throws Exception {
            BlogPostEntity post = blog(teamId, null, adminId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1));
            post.softDelete();
            em.flush();
            assertCreateSameAsMissing("BLOG_POST", post.getId());
        }

        @Test
        @DisplayName("他チームの他人の下書き → 不在と同一（スコープ判定より前に拒否し、実在を悟らせない）")
        void othersDraftInOtherTeam_sameAsMissing() throws Exception {
            Long postId = blog(otherTeamId, null, otherId, PostStatus.DRAFT, null).getId();
            assertCreateSameAsMissing("BLOG_POST", postId);
        }

        @Test
        @DisplayName("他人の個人ブログの下書き → 不在と同一")
        void othersPersonalDraft_sameAsMissing() throws Exception {
            BlogPostEntity post = BlogPostEntity.builder().userId(otherId).authorId(otherId).title("N9 個人下書き " + key)
                    .slug("n9p-" + UUID.randomUUID()).body("本文").visibility(Visibility.MEMBERS_ONLY)
                    .postType(PostType.BLOG).status(PostStatus.DRAFT).build();
            em.persist(post);
            em.flush();
            assertCreateSameAsMissing("BLOG_POST", post.getId());
        }

        @Test
        @DisplayName("タイムラインの予約投稿・論理削除 → 不在と同一")
        void scheduledAndDeletedTimeline_sameAsMissing() throws Exception {
            Long scheduled = timeline(teamId, com.mannschaft.app.timeline.PostStatus.SCHEDULED).getId();
            assertCreateSameAsMissing("TIMELINE_POST", scheduled);

            TimelinePostEntity deleted = timeline(teamId, com.mannschaft.app.timeline.PostStatus.PUBLISHED);
            deleted.softDelete();
            em.flush();
            assertCreateSameAsMissing("TIMELINE_POST", deleted.getId());
        }

        private void assertCreateSameAsMissing(String sourceType, Long sourceId) throws Exception {
            MvcResult missing = perform(adminId, create(sourceType, MISSING_ID));
            assertThat(missing.getResponse().getStatus()).isEqualTo(404);
            assertThat(json(missing).path("error").path("code").asText()).isEqualTo("ANNOUNCE_006");

            MvcResult actual = perform(adminId, create(sourceType, sourceId));
            assertThat(actual.getResponse().getStatus()).as("status は不在と同一").isEqualTo(404);
            assertThat(json(actual)).as("本文（error.code・message）は不在と同一").isEqualTo(json(missing));
            assertThat(feedCount(AnnouncementSourceType.valueOf(sourceType), sourceId)).as("行を作らない").isZero();
        }

        private MockHttpServletRequestBuilder create(String sourceType, Long sourceId) {
            return post("/api/v1/teams/{t}/announcements", teamId).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sourceType\":\"%s\",\"sourceId\":%d}".formatted(sourceType, sourceId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ID 指定の経路
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ID 指定の経路: 元が公開済みでないお知らせは不在のお知らせ ID と同一")
    class IdPaths {

        @Test
        @DisplayName("チーム ピン留め（ADMIN）: 公開済みは 200・下書きに戻した元は不在と同一（タイトルを返さない）")
        void teamPin() throws Exception {
            BlogPostEntity post = blog(teamId, null, memberId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1));
            Long feedId = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST, post.getId());
            Long controlId = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST,
                    blog(teamId, null, memberId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1)).getId());
            assertThat(perform(adminId, teamPin(controlId)).getResponse().getStatus()).as("公開済みは 200").isEqualTo(200);

            em.find(BlogPostEntity.class, post.getId()).unpublish(LocalDateTime.now());
            em.flush();
            assertSameAsMissing(adminId, teamPin(feedId), teamPin(MISSING_ID));
            assertThat(isPinned(feedId)).as("拒否された要求でピン留めされない").isFalse();
        }

        @Test
        @DisplayName("チーム ピン留め（ADMIN）: 元を論理削除したお知らせは不在と同一")
        void teamPinDeletedSource() throws Exception {
            TimelinePostEntity post = timeline(teamId, com.mannschaft.app.timeline.PostStatus.PUBLISHED);
            Long feedId = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.TIMELINE_POST, post.getId());
            post.softDelete();
            em.flush();
            assertSameAsMissing(adminId, teamPin(feedId), teamPin(MISSING_ID));
        }

        @Test
        @DisplayName("組織 ピン留め（ADMIN）: 元が下書きのお知らせは不在と同一")
        void orgPin() throws Exception {
            Long feedId = feed(AnnouncementScopeType.ORGANIZATION, orgId, AnnouncementSourceType.BLOG_POST,
                    blog(null, orgId, memberId, PostStatus.DRAFT, null).getId());
            assertSameAsMissing(adminId,
                    pinRequest(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgId, feedId)),
                    pinRequest(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgId, MISSING_ID)));
        }

        @Test
        @DisplayName("チーム 単件既読: 元が下書きのお知らせは不在と同一・既読行を作らない")
        void teamRead() throws Exception {
            Long feedId = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST,
                    blog(teamId, null, adminId, PostStatus.DRAFT, null).getId());
            assertSameAsMissing(memberId,
                    post("/api/v1/teams/{t}/announcements/{id}/read", teamId, feedId),
                    post("/api/v1/teams/{t}/announcements/{id}/read", teamId, MISSING_ID));
            assertThat(countRead(feedId, memberId)).isZero();
        }

        @Test
        @DisplayName("組織 read-batch: 元が下書き・論理削除のお知らせは黙って除外し、公開済みだけ既読にする")
        void orgReadBatch() throws Exception {
            Long published = feed(AnnouncementScopeType.ORGANIZATION, orgId, AnnouncementSourceType.BLOG_POST,
                    blog(null, orgId, adminId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1)).getId());
            Long draft = feed(AnnouncementScopeType.ORGANIZATION, orgId, AnnouncementSourceType.BLOG_POST,
                    blog(null, orgId, adminId, PostStatus.DRAFT, null).getId());
            BlogPostEntity deletedPost = blog(null, orgId, adminId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1));
            Long deleted = feed(AnnouncementScopeType.ORGANIZATION, orgId, AnnouncementSourceType.BLOG_POST,
                    deletedPost.getId());
            deletedPost.softDelete();
            em.flush();

            MvcResult result = perform(memberId, post("/api/v1/organizations/{o}/announcements/read-batch", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"feedIds\":[%d,%d,%d]}".formatted(published, draft, deleted)));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(result).path("data").path("markedCount").asInt()).as("公開済みの 1 件だけ").isEqualTo(1);
            assertThat(countRead(published, memberId)).isEqualTo(1);
            assertThat(countRead(draft, memberId)).as("下書きの元のお知らせに既読行を作らない").isZero();
            assertThat(countRead(deleted, memberId)).as("論理削除の元のお知らせに既読行を作らない").isZero();
        }

        @Test
        @DisplayName("インボックスの可視判定: 元が公開済みなら可視・下書きなら不可視")
        void inboxIsVisibleTo() {
            Long published = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST,
                    blog(teamId, null, adminId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1)).getId());
            Long draft = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST,
                    blog(teamId, null, adminId, PostStatus.DRAFT, null).getId());
            em.flush();
            assertThat(inboxAdapter.isVisibleTo(memberId, published)).isTrue();
            assertThat(inboxAdapter.isVisibleTo(memberId, draft)).as("下書きの元のお知らせは不可視").isFalse();
        }

        @Test
        @DisplayName("F00 Resolver: 元が公開済みなら閲覧可・下書き/論理削除なら不可（不在と同じ）")
        void resolverCanView() {
            Long published = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST,
                    blog(teamId, null, adminId, PostStatus.PUBLISHED, LocalDateTime.now().minusDays(1)).getId());
            Long draft = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST,
                    blog(teamId, null, adminId, PostStatus.DRAFT, null).getId());
            TimelinePostEntity deletedPost = timeline(teamId, com.mannschaft.app.timeline.PostStatus.PUBLISHED);
            Long deleted = feed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.TIMELINE_POST,
                    deletedPost.getId());
            deletedPost.softDelete();
            em.flush();
            assertThat(visibilityResolver.canView(published, memberId)).isTrue();
            assertThat(visibilityResolver.canView(draft, memberId)).as("下書きの元のお知らせは閲覧不可").isFalse();
            assertThat(visibilityResolver.canView(deleted, memberId)).as("論理削除の元のお知らせは閲覧不可").isFalse();
            assertThat(visibilityResolver.filterAccessible(List.of(published, draft, deleted), memberId))
                    .containsExactly(published);
        }

        private MockHttpServletRequestBuilder teamPin(Long feedId) {
            return pinRequest(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamId, feedId));
        }

        private MockHttpServletRequestBuilder pinRequest(MockHttpServletRequestBuilder builder) {
            return builder.contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}");
        }

        private void assertSameAsMissing(Long actorId, MockHttpServletRequestBuilder actual,
                                         MockHttpServletRequestBuilder missing) throws Exception {
            MvcResult missingResult = perform(actorId, missing);
            assertThat(missingResult.getResponse().getStatus()).isEqualTo(404);
            assertThat(json(missingResult).path("error").path("code").asText()).isEqualTo("ANNOUNCE_001");

            MvcResult actualResult = perform(actorId, actual);
            assertThat(actualResult.getResponse().getStatus())
                    .as("元が公開済みでないお知らせの status は不在 ID と同一であること（存在オラクル）")
                    .isEqualTo(missingResult.getResponse().getStatus());
            assertThat(json(actualResult))
                    .as("本文（error.code・message 含む）は不在 ID と同一であること（タイトル・抜粋を返さない）")
                    .isEqualTo(json(missingResult));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MvcResult perform(Long actorId, MockHttpServletRequestBuilder request) throws Exception {
        em.flush();
        em.clear();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of()));
        request.header("Accept-Language", "ja");
        MvcResult result = mockMvc.perform(request).andReturn();
        em.flush();
        return result;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private BlogPostEntity blog(Long scopeTeamId, Long scopeOrgId, Long authorId, PostStatus status,
                                LocalDateTime publishedAt) {
        BlogPostEntity post = BlogPostEntity.builder().teamId(scopeTeamId).organizationId(scopeOrgId)
                .authorId(authorId).title("N9 記事 " + key).slug("n9-" + UUID.randomUUID()).body("本文")
                .visibility(Visibility.MEMBERS_ONLY).postType(PostType.BLOG).status(status)
                .publishedAt(publishedAt).build();
        em.persist(post);
        em.flush();
        return post;
    }

    private TimelinePostEntity timeline(Long scopeTeamId, com.mannschaft.app.timeline.PostStatus status) {
        TimelinePostEntity post = TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(scopeTeamId)
                .userId(adminId).content("N9 投稿 " + key).status(status).build();
        em.persist(post);
        em.flush();
        return post;
    }

    private Long feed(AnnouncementScopeType scopeType, Long scopeId, AnnouncementSourceType type, Long sourceId) {
        AnnouncementFeedEntity feed = feedRepository.saveAndFlush(AnnouncementFeedEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).sourceType(type).sourceId(sourceId)
                .authorId(memberId).titleCache("N9 秘匿タイトル " + key).excerptCache("N9 秘匿抜粋")
                .visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE).build());
        return feed.getId();
    }

    private long feedCount(AnnouncementSourceType type, Long sourceId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_feeds "
                        + "WHERE source_type = :t AND source_id = :s")
                .setParameter("t", type.name()).setParameter("s", sourceId).getSingleResult()).longValue();
    }

    private long countRead(Long feedId, Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_read_status "
                        + "WHERE announcement_feed_id = :f AND user_id = :u")
                .setParameter("f", feedId).setParameter("u", userId).getSingleResult()).longValue();
    }

    private boolean isPinned(Long feedId) {
        Object value = em.createNativeQuery("SELECT is_pinned FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult();
        return value instanceof Boolean b ? b : ((Number) value).intValue() == 1;
    }

    private Long insertTeam(String label) {
        String name = "N9P チーム" + label + " " + key;
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (:n, 'PUBLIC', 1, 0, 0, :s, NOW(), NOW())")
                .setParameter("n", name).setParameter("s", "n9p" + label + "-" + key).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "N9P 組織 " + key;
        em.createNativeQuery("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:n, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :s, NOW(), NOW())")
                .setParameter("n", name).setParameter("s", "n9po-" + key).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private Long insertUser(String label) {
        String email = "n9p-" + label + "-" + key + "@example.com";
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:e, 'N9P', 'テスト', 'N9P テスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', "
                        + "'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("e", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email).getSingleResult()).longValue();
    }
}
