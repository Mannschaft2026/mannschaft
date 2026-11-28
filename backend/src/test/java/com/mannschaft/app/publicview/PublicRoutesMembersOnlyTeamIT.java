package com.mannschaft.app.publicview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261124-0159 PR-P: 公開 EP・トークン経路の表駆動 IT（F00 §20.5.3・AC-0159-08）。
 *
 * <p>未ログイン・ログイン済み部外者・外されたサポーター（是正前データを模したフィクスチャ）の各閲覧者について、
 * 非公開チームでは表の「非公開チームでの扱い」どおりの応答になり、本文に中身が出ないことを確かめる。
 * サイネージ・ファイルリンク・公開マーケットの行は各ドメインの IT（{@code SignageMembersOnlyTeamCeilingIT}・
 * {@code SignageSubscriptionAuthzIntegrationTest}・{@code SharedFileLinkMembersOnlyTeamIT}・
 * {@code MarketMembersOnlyTeamCeilingIT}）が担い、活動記録の等価性は {@code ActivityPublicContractIT}（AC-32）が担う。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 公開 EP・トークン経路の親チーム上限（表駆動）")
class PublicRoutesMembersOnlyTeamIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BlogPostRepository postRepository;
    @PersistenceContext private EntityManager em;

    private ScopeContentFixture fixture;
    private String marker;
    private Long cappedPostId;
    private Long otherPostId;
    private Long authorId;

    private record Resp(int status, String code, String message, String body) {
        boolean sameAs(Resp other) {
            return status == other.status && Objects.equals(code, other.code)
                    && Objects.equals(message, other.message);
        }
    }

    @BeforeEach
    void setUp() {
        fixture = ScopeContentFixture.create(em);
        ScopeContentFixture.setTeamVisibility(em, fixture.otherTeamId, "PUBLIC");
        marker = "c59pub" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        authorId = fixture.user(Actor.MEMBER);
        em.createNativeQuery("UPDATE users SET public_profile_enabled = TRUE WHERE id = :id")
                .setParameter("id", authorId).executeUpdate();
        cappedPostId = seedPost(fixture.teamId, marker + "-capped");
        otherPostId = seedPost(fixture.otherTeamId, marker + "-other");
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 未ログイン（null）・ログイン済み部外者・外されたサポーター。 */
    private List<Long> viewers() {
        return Arrays.asList(null, fixture.user(Actor.OUTSIDER), fixture.user(Actor.SUPPORTER));
    }

    @Test
    @DisplayName("回帰固定: 公開チームページ・記事・イベント・タイムライン・活動・FAQ は非公開チームに対して 404 で、本文にチーム名・記事名を含めない")
    void 既存の非公開チーム応答() throws Exception {
        String teamName = (String) em.createNativeQuery("SELECT name FROM teams WHERE id = :id")
                .setParameter("id", fixture.teamId).getSingleResult();
        List<String> paths = List.of(
                "/api/v1/public/teams/" + fixture.teamSlug,
                "/api/v1/public/teams/" + fixture.teamId + "/posts",
                "/api/v1/public/teams/" + fixture.teamId + "/posts/" + cappedPostId,
                "/api/v1/public/teams/" + fixture.teamId + "/events",
                "/api/v1/public/teams/" + fixture.teamId + "/timeline-posts",
                "/api/v1/public/teams/" + fixture.teamId + "/activities",
                "/api/v1/public/teams/" + fixture.teamId + "/faqs");
        SoftAssertions soft = new SoftAssertions();
        for (String path : paths) {
            for (Long viewer : viewers()) {
                Resp resp = perform(get(path), viewer);
                soft.assertThat(resp.status()).as("%s viewer=%s %s", path, viewer, resp).isEqualTo(404);
                soft.assertThat(resp.body()).as("%s viewer=%s", path, viewer)
                        .doesNotContain(teamName).doesNotContain(marker);
            }
        }
        soft.assertAll();
    }

    @Test
    @DisplayName("回帰固定: サイトマップに非公開チームの URL を含めない")
    void サイトマップ() throws Exception {
        Resp resp = perform(get("/sitemap.xml"), null);
        assertThat(resp.body()).doesNotContain(fixture.teamSlug);
    }

    @Test
    @DisplayName("公開ユーザープロフィールの投稿一覧は、非公開チームのスコープの投稿を含めない（公開チームの投稿は従来どおり含む）")
    void ユーザーの公開投稿一覧() throws Exception {
        for (Long viewer : viewers()) {
            Resp resp = perform(get("/api/v1/public/users/{userId}/posts", authorId), viewer);
            assertThat(resp.status()).as("viewer=%s", viewer).isEqualTo(200);
            assertThat(resp.body()).as("viewer=%s", viewer)
                    .contains(marker + "-other").doesNotContain(marker + "-capped");
        }
    }

    @Test
    @DisplayName("公開記事コメント: 非公開チームの記事は一覧・投稿とも不在の記事と同一応答")
    void 公開記事コメント() throws Exception {
        Long absentPost = Long.MAX_VALUE;
        for (Long viewer : viewers()) {
            Resp absent = perform(get("/api/v1/public/blog-posts/{postId}/comments", absentPost), viewer);
            Resp actual = perform(get("/api/v1/public/blog-posts/{postId}/comments", cappedPostId), viewer);
            assertThat(actual.sameAs(absent)).as("一覧 viewer=%s actual=%s absent=%s", viewer, actual, absent).isTrue();
            assertThat(perform(get("/api/v1/public/blog-posts/{postId}/comments", otherPostId), viewer).status())
                    .as("公開チームの記事は従来どおり 200 viewer=%s", viewer).isEqualTo(200);
        }
        for (Long viewer : List.of(fixture.user(Actor.OUTSIDER), fixture.user(Actor.SUPPORTER))) {
            String body = objectMapper.writeValueAsString(java.util.Map.of("content", "上限試練コメント"));
            Resp absent = perform(post("/api/v1/public/blog-posts/{postId}/comments", absentPost)
                    .contentType(MediaType.APPLICATION_JSON).content(body), viewer);
            Resp actual = perform(post("/api/v1/public/blog-posts/{postId}/comments", cappedPostId)
                    .contentType(MediaType.APPLICATION_JSON).content(body), viewer);
            assertThat(actual.sameAs(absent)).as("投稿 viewer=%s actual=%s absent=%s", viewer, actual, absent).isTrue();
        }
    }

    @Test
    @DisplayName("確認トークン: 宛先がそのチームで MEMBER 未満なら不在トークンと同一応答で確認済みにならない。MEMBER の宛先は従来どおり 200")
    void 確認トークン() throws Exception {
        ConfirmableNotificationEntity notification = seedNotification(fixture.teamId);
        String supporterToken = seedRecipient(notification, fixture.user(Actor.SUPPORTER));
        String memberToken = seedRecipient(notification, fixture.user(Actor.MEMBER));
        em.flush();
        em.clear();

        Resp absent = perform(post("/api/v1/public/confirm/{token}", UUID.randomUUID().toString()), null);
        Resp actual = perform(post("/api/v1/public/confirm/{token}", supporterToken), null);
        assertThat(actual.sameAs(absent)).as("actual=%s absent=%s", actual, absent).isTrue();
        assertThat(isConfirmed(supporterToken)).isFalse();

        assertThat(perform(post("/api/v1/public/confirm/{token}", memberToken), null).status()).isEqualTo(200);
        assertThat(isConfirmed(memberToken)).isTrue();
    }

    @Test
    @DisplayName("確認トークン: 公開チーム（SUPPORTERS_AND_ABOVE）ではサポーター宛てのトークンも従来どおり 200")
    void 確認トークン_公開チーム() throws Exception {
        ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "SUPPORTERS_AND_ABOVE");
        ConfirmableNotificationEntity notification = seedNotification(fixture.teamId);
        String supporterToken = seedRecipient(notification, fixture.user(Actor.SUPPORTER));
        em.flush();
        em.clear();
        assertThat(perform(post("/api/v1/public/confirm/{token}", supporterToken), null).status()).isEqualTo(200);
    }

    // ─── ヘルパー ─────────────────────────────────────────

    private Resp perform(MockHttpServletRequestBuilder builder, Long userId) throws Exception {
        if (userId == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = null;
        String message = null;
        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode error = objectMapper.readTree(body).path("error");
            code = error.path("code").isMissingNode() ? null : error.path("code").asText();
            message = error.path("message").isMissingNode() ? null : error.path("message").asText();
        }
        return new Resp(result.getResponse().getStatus(), code, message, body);
    }

    private Long seedPost(Long teamId, String title) {
        return postRepository.saveAndFlush(BlogPostEntity.builder()
                .teamId(teamId)
                .authorId(authorId)
                .title(title)
                .slug("c59-" + UUID.randomUUID())
                .body("本文 " + title)
                .postType(PostType.BLOG)
                .visibility(Visibility.PUBLIC)
                .priority(PostPriority.NORMAL)
                .status(PostStatus.PUBLISHED)
                .publishedAt(java.time.LocalDateTime.now().minusHours(1))
                .readingTimeMinutes((short) 1)
                .build()).getId();
    }

    private ConfirmableNotificationEntity seedNotification(Long teamId) {
        ConfirmableNotificationEntity entity = ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(teamId)
                .title("上限試練の確認通知 " + marker)
                .totalRecipientCount(2)
                .deliveredCount(2)
                .unconfirmedCount(2)
                .createdBy(em.getReference(UserEntity.class, fixture.user(Actor.ADMIN)))
                .build();
        em.persist(entity);
        return entity;
    }

    private String seedRecipient(ConfirmableNotificationEntity notification, Long userId) {
        String token = UUID.randomUUID().toString();
        em.persist(ConfirmableNotificationRecipientEntity.builder()
                .confirmableNotification(notification)
                .user(em.getReference(UserEntity.class, userId))
                .confirmToken(token)
                .build());
        return token;
    }

    private boolean isConfirmed(String token) {
        Object v = em.createNativeQuery(
                        "SELECT is_confirmed FROM confirmable_notification_recipients WHERE confirm_token = :t")
                .setParameter("t", token).getSingleResult();
        return v instanceof Boolean b ? b : ((Number) v).intValue() != 0;
    }
}
