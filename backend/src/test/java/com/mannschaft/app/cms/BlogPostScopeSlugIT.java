package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ブログ記事応答のスコープ slug（{@code scope.teamSlug} / {@code scope.organizationSlug}）の契約テスト。
 *
 * <p>編集画面は公開・削除・承認の後にチーム／組織のページへ遷移する。チーム・組織ページの URL は slug が前提で、
 * 数値 ID の URL は 404 になる。FE が「マイチーム」一覧から slug を引くと、所属行の無い管理者
 * （SYSTEM_ADMIN・親組織の管理者）やアーカイブ済み・51件目以降のチームで引けない。そのため記事応答そのものに
 * slug を載せる。作者がスコープに所属していなくても slug が返ることを、所属行を作らない作者で確かめる。</p>
 *
 * <p>論理削除済みのチーム・組織は通常の slug 解決（{@code TeamService#getSlugsByIds}）に倣って null を返す。
 * キー自体は常に応答に含める（FE の型は省略不可の {@code string | null}）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ブログ記事応答のスコープ slug 契約")
class BlogPostScopeSlugIT extends AbstractMySqlIntegrationTest {

    private static final String MY_POST = "/api/v1/users/me/blog/posts/{id}";
    private static final String MY_POSTS = "/api/v1/users/me/blog/posts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private String key;
    private Long teamId;
    private String teamSlug;
    private Long orgId;
    private String orgSlug;
    /** どのチーム・組織にも所属行を持たない作者（SYSTEM_ADMIN・親組織の管理者と同じく /me/teams に載らない）。 */
    private Long authorId;

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        teamSlug = "blog-slug-team-" + key;
        orgSlug = "blog-slug-org-" + key;
        teamId = TeamOrgFixtureHelper.insertTeam(em, "slug 契約のチーム", teamSlug);
        orgId = TeamOrgFixtureHelper.insertOrganization(em, "slug 契約の組織", orgSlug);
        authorId = saveUser("author");
        em.flush();
    }

    @Test
    @DisplayName("チーム記事: 所属行の無い作者でも scope.teamSlug にチームの slug が返る")
    void チーム記事はteamSlugを返す() throws Exception {
        Long postId = savePost(teamId, null);

        JsonNode scope = getMyPostScope(postId);

        assertThat(scope.path("teamId").asLong()).isEqualTo(teamId);
        assertThat(scope.path("teamSlug").asText()).isEqualTo(teamSlug);
        assertThat(scope.has("organizationSlug")).isTrue();
        assertThat(scope.get("organizationSlug").isNull()).isTrue();
    }

    @Test
    @DisplayName("組織記事: scope.organizationSlug に組織の slug が返る")
    void 組織記事はorganizationSlugを返す() throws Exception {
        Long postId = savePost(null, orgId);

        JsonNode scope = getMyPostScope(postId);

        assertThat(scope.path("organizationSlug").asText()).isEqualTo(orgSlug);
        assertThat(scope.has("teamSlug")).isTrue();
        assertThat(scope.get("teamSlug").isNull()).isTrue();
    }

    @Test
    @DisplayName("個人記事: teamSlug・organizationSlug とも null（キーは返す）")
    void 個人記事はslugがnull() throws Exception {
        Long postId = savePost(null, null);

        JsonNode scope = getMyPostScope(postId);

        assertThat(scope.has("teamSlug")).isTrue();
        assertThat(scope.get("teamSlug").isNull()).isTrue();
        assertThat(scope.has("organizationSlug")).isTrue();
        assertThat(scope.get("organizationSlug").isNull()).isTrue();
    }

    @Test
    @DisplayName("アーカイブ済みのチームの記事も slug が返る（/me/teams に載らないチーム）")
    void アーカイブ済みチームでもslugを返す() throws Exception {
        em.createNativeQuery("UPDATE teams SET archived_at = NOW() WHERE id = :id")
                .setParameter("id", teamId).executeUpdate();
        Long postId = savePost(teamId, null);

        JsonNode scope = getMyPostScope(postId);

        assertThat(scope.path("teamSlug").asText()).isEqualTo(teamSlug);
    }

    @Test
    @DisplayName("論理削除済みのチームの記事は teamSlug が null（通常の slug 解決の作法に従う）")
    void 論理削除済みチームはslugがnull() throws Exception {
        Long postId = savePost(teamId, null);
        em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", teamId).executeUpdate();
        em.flush();
        em.clear();

        JsonNode scope = getMyPostScope(postId);

        assertThat(scope.path("teamId").asLong()).isEqualTo(teamId);
        assertThat(scope.has("teamSlug")).isTrue();
        assertThat(scope.get("teamSlug").isNull()).isTrue();
    }

    @Test
    @DisplayName("自分の記事一覧: チーム・組織・個人が混在しても各記事に slug が付く")
    void 一覧の各記事にslugが付く() throws Exception {
        // 一覧は F00 可視性で絞るため、作者をスコープの MEMBER にしておく（slug の付与は所属と無関係）。
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        Long teamPost = savePost(teamId, null);
        Long orgPost = savePost(null, orgId);
        Long personalPost = savePost(null, null);

        String body = mockMvc.perform(get(MY_POSTS).with(user(authorId.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<Long, JsonNode> scopes = new HashMap<>();
        for (JsonNode post : objectMapper.readTree(body).path("data")) {
            scopes.put(post.path("id").asLong(), post.path("scope"));
        }

        assertThat(scopes).containsKeys(teamPost, orgPost, personalPost);
        assertThat(scopes.get(teamPost).path("teamSlug").asText()).isEqualTo(teamSlug);
        assertThat(scopes.get(orgPost).path("organizationSlug").asText()).isEqualTo(orgSlug);
        assertThat(scopes.get(personalPost).get("teamSlug").isNull()).isTrue();
        assertThat(scopes.get(personalPost).get("organizationSlug").isNull()).isTrue();
    }

    private JsonNode getMyPostScope(Long postId) throws Exception {
        String body = mockMvc.perform(get(MY_POST, postId).with(user(authorId.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("scope");
    }

    private Long savePost(Long scopeTeamId, Long scopeOrgId) {
        BlogPostEntity entity = BlogPostEntity.builder()
                .teamId(scopeTeamId).organizationId(scopeOrgId).userId(authorId).authorId(authorId)
                .title("slug 契約の記事").slug("slug-contract-" + UUID.randomUUID().toString().substring(0, 12))
                .body("本文").postType(PostType.BLOG).visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build();
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    private Long saveUser(String role) {
        UserEntity entity = UserEntity.builder().email("blog-slug-" + role + "-" + key + "@example.com")
                .lastName("試練").firstName(role).displayName("slug 契約 " + role).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
