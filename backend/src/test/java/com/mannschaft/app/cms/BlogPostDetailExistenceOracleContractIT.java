package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.service.BlogReactionService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 戦役B「ブログ記事詳細の存在オラクル（CMP-261008-1701）」の契約 IT（番人・C9）。
 *
 * <p>記事詳細 GET（{@code BlogPostController#getPostBySlug}・{@code PersonalBlogController#getUserPost}）で、
 * 「記事は在るが閲覧できない」応答と「記事が無い」応答が<b>レスポンス全体で</b>見分けられないことを固定する。
 * 比較するのは HTTP ステータス・本文（code・message・fieldErrors を含む JSON 全体）・ヘッダー全体
 * （Cache-Control・Content-Type・x-error-code 等）。リクエストごとに必ず値が変わる相関 ID・レート制限の残数／
 * リセット時刻だけは比較から除く（{@link #VOLATILE_HEADERS}）。</p>
 *
 * <p>チーム・組織・個人の各経路と、他テナント指定（別チームの teamId で自チームの記事 slug を指定）を含める。
 * 閲覧不可の作り方は「PUBLIC のチーム／組織に所属しない利用者が MEMBERS_ONLY 記事を開く」「他人の PRIVATE 個人記事を
 * 開く」「非作者が下書きを開く」。スコープ門（{@code BlogScopeAccessGuard}）は PUBLIC スコープなので通過し、
 * 記事の可視性判定（{@code ContentVisibilityChecker#assertCanView}）まで到達する。</p>
 *
 * <p>未ログインは SecurityConfig の deny-by-default（401）で先に弾かれるため、本 IT では扱わない
 * （viewer=null の畳み込みは {@code BlogPostDetailExistenceOracleTest} が固定する）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261008-1701 ブログ記事詳細の存在オラクル 契約 IT（閲覧不可と不在の応答が全体で等価）")
class BlogPostDetailExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    private static final String DETAIL = "/api/v1/blog/posts/{slug}";
    private static final String PERSONAL_DETAIL = "/api/v1/users/{userId}/blog/posts/{slug}";

    /** リクエストごとに値が変わり、存在とは無関係なヘッダー（比較から除外する）。 */
    private static final Set<String> VOLATILE_HEADERS = Set.of(
            "x-request-id", "x-ratelimit-remaining", "x-ratelimit-reset");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private BlogReactionService reactionService;

    @PersistenceContext
    private EntityManager em;

    private String key;

    private Long teamId;
    private String teamSlug;
    /** 他テナント（outsider だけが所属する別チーム）。 */
    private Long otherTeamId;
    private String otherTeamSlug;
    private Long orgId;
    private String orgSlug;

    private Long authorId;
    private Long memberId;
    private Long outsiderId;
    private Long otherAuthorId;

    private String teamMembersOnlySlug;
    private Long teamMembersOnlyPostId;
    private String teamDraftSlug;
    private String orgMembersOnlySlug;
    private String personalPrivateSlug;
    private String otherAuthorPublicSlug;

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        teamSlug = "oracle-team-" + key;
        otherTeamSlug = "oracle-other-" + key;
        orgSlug = "oracle-org-" + key;
        teamId = TeamOrgFixtureHelper.insertTeam(em, "存在オラクルのチーム", teamSlug);
        otherTeamId = TeamOrgFixtureHelper.insertTeam(em, "存在オラクルの他チーム", otherTeamSlug);
        orgId = TeamOrgFixtureHelper.insertOrganization(em, "存在オラクルの組織", orgSlug);

        authorId = saveUser("author");
        memberId = saveUser("member");
        outsiderId = saveUser("outsider");
        otherAuthorId = saveUser("other-author");
        em.flush();

        MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, outsiderId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);

        BlogPostEntity teamMembersOnly = savePost(teamId, null, null, authorId,
                Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
        teamMembersOnlySlug = teamMembersOnly.getSlug();
        teamMembersOnlyPostId = teamMembersOnly.getId();
        teamDraftSlug = savePost(teamId, null, null, authorId, Visibility.MEMBERS_ONLY, PostStatus.DRAFT).getSlug();
        orgMembersOnlySlug = savePost(null, orgId, null, authorId,
                Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED).getSlug();
        personalPrivateSlug = savePost(null, null, authorId, authorId,
                Visibility.PRIVATE, PostStatus.PUBLISHED).getSlug();
        otherAuthorPublicSlug = savePost(null, null, otherAuthorId, otherAuthorId,
                Visibility.PUBLIC, PostStatus.PUBLISHED).getSlug();
        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // C1: 権限者は従来どおり 200（回帰）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("C1 権限者は下書き・限定記事を 200 で取得できる（回帰）")
    class Authorized {

        @Test
        @DisplayName("C1: チームのメンバーは MEMBERS_ONLY 記事を slug 形の teamId で 200 取得できる")
        void C1_メンバーはチーム限定記事を取得できる() throws Exception {
            MockHttpServletResponse res = mockMvc.perform(get(DETAIL, teamMembersOnlySlug)
                            .param("teamId", teamSlug).with(user(memberId.toString())))
                    .andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("C1: 作者は自分のチーム下書きを 200 取得できる")
        void C1_作者は下書きを取得できる() throws Exception {
            MockHttpServletResponse res = mockMvc.perform(get(DETAIL, teamDraftSlug)
                            .param("teamId", teamId.toString()).with(user(authorId.toString())))
                    .andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("C1: 組織のメンバーは MEMBERS_ONLY 記事を slug 形の organizationId で 200 取得できる")
        void C1_メンバーは組織限定記事を取得できる() throws Exception {
            MockHttpServletResponse res = mockMvc.perform(get(DETAIL, orgMembersOnlySlug)
                            .param("organizationId", orgSlug).with(user(memberId.toString())))
                    .andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("C1: 作者は自分の PRIVATE 個人記事を個人経路で 200 取得できる")
        void C1_作者は個人記事を取得できる() throws Exception {
            MockHttpServletResponse res = mockMvc.perform(get(PERSONAL_DETAIL, authorId, personalPrivateSlug)
                            .with(user(authorId.toString())))
                    .andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // C2/C3/C4: チーム・組織経路
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("C2/C3/C4 チーム・組織経路: 閲覧不可と不在が応答全体で等価な 404 CMS_001")
    class ScopedRoutes {

        @Test
        @DisplayName("C2/C3/C9: 非メンバーがチームの MEMBERS_ONLY 記事（slug 形 teamId）を開くと、不在 slug と応答全体が等価な 404 CMS_001")
        void C2_C3_チーム限定記事_非メンバーは不在と等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, teamMembersOnlySlug).param("teamId", teamSlug), outsiderId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("teamId", teamSlug), outsiderId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C2/C3/C9: 数値の teamId でも、非メンバーの閲覧不可と不在 slug の応答全体が等価")
        void C2_C3_チーム限定記事_数値teamIdでも不在と等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, teamMembersOnlySlug).param("teamId", teamId.toString()),
                    outsiderId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("teamId", teamId.toString()),
                    outsiderId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C2/C3/C9: チーム外の非メンバーが下書きを開いても、不在 slug と応答全体が等価")
        void C2_C3_下書き_非メンバーは不在と等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, teamDraftSlug).param("teamId", teamSlug), outsiderId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("teamId", teamSlug), outsiderId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C2/C3/C9: 作者でないチームメンバーが下書きを開いても、不在 slug と応答全体が等価")
        void C2_C3_下書き_作者でないメンバーは不在と等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, teamDraftSlug).param("teamId", teamSlug), memberId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("teamId", teamSlug), memberId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C2/C3: 未実装のプレビュートークン付きでも、閲覧不可と不在 slug の応答全体が等価（403→404）")
        void C2_C3_プレビュートークン付き_不在と等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, teamDraftSlug).param("teamId", teamSlug)
                    .param("previewToken", "tok-" + key), outsiderId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("teamId", teamSlug)
                    .param("previewToken", "tok-" + key), outsiderId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C2/C3/C9: 非メンバーが組織の MEMBERS_ONLY 記事（slug 形 organizationId）を開くと、不在 slug と応答全体が等価")
        void C2_C3_組織限定記事_非メンバーは不在と等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, orgMembersOnlySlug).param("organizationId", orgSlug),
                    outsiderId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("organizationId", orgSlug),
                    outsiderId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C4/C9: 他テナント（自分のチームの teamId で他チームの記事 slug を指定）・他チーム直指定の閲覧不可・不在がすべて等価")
        void C4_他テナント指定は不在と等価() throws Exception {
            Snapshot crossTenant = snapshot(get(DETAIL, teamMembersOnlySlug).param("teamId", otherTeamSlug),
                    outsiderId);
            Snapshot absentInOwnTeam = snapshot(get(DETAIL, "no-such-" + key).param("teamId", otherTeamSlug),
                    outsiderId);
            Snapshot invisibleInOtherTeam = snapshot(get(DETAIL, teamMembersOnlySlug).param("teamId", teamSlug),
                    outsiderId);

            assertNotFound(absentInOwnTeam);
            assertThat(crossTenant).isEqualTo(absentInOwnTeam);
            assertThat(invisibleInOtherTeam).isEqualTo(absentInOwnTeam);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // C5: 個人経路
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("C5 個人経路: 閲覧不可・不在・他人の記事・チーム/組織記事が等価な 404 CMS_001")
    class PersonalRoute {

        @Test
        @DisplayName("C5/C9: GET /users/{userId}/blog/posts/{slug} で閲覧不可（他人の PRIVATE）・不在・他人の記事・チーム記事・組織記事が応答全体で等価")
        void C5_個人経路の閲覧不可と不在が等価() throws Exception {
            Snapshot absent = snapshot(get(PERSONAL_DETAIL, authorId, "no-such-" + key), outsiderId);
            Snapshot invisible = snapshot(get(PERSONAL_DETAIL, authorId, personalPrivateSlug), outsiderId);
            Snapshot othersPost = snapshot(get(PERSONAL_DETAIL, authorId, otherAuthorPublicSlug), outsiderId);
            Snapshot teamPost = snapshot(get(PERSONAL_DETAIL, authorId, teamMembersOnlySlug), outsiderId);
            Snapshot orgPost = snapshot(get(PERSONAL_DETAIL, authorId, orgMembersOnlySlug), outsiderId);

            assertNotFound(absent);
            assertThat(othersPost).isEqualTo(absent);
            assertThat(teamPost).isEqualTo(absent);
            assertThat(orgPost).isEqualTo(absent);
            assertThat(invisible).isEqualTo(absent);
        }

        @Test
        @DisplayName("C5/C9: 汎用経路 GET /blog/posts/{slug}?userId= でも他人の PRIVATE 個人記事と不在 slug の応答全体が等価")
        void C5_汎用経路のuserId指定でも等価() throws Exception {
            Snapshot invisible = snapshot(get(DETAIL, personalPrivateSlug).param("userId", authorId.toString()),
                    outsiderId);
            Snapshot absent = snapshot(get(DETAIL, "no-such-" + key).param("userId", authorId.toString()),
                    outsiderId);

            assertNotFound(absent);
            assertThat(invisible).isEqualTo(absent);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // C7: 副作用なし
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C7: 閲覧不可記事を叩くと 404 CMS_001 で、view_count は増えずリアクション取得も走らない")
    void C7_閲覧不可記事は副作用なしで404() throws Exception {
        Number before = viewCount(teamMembersOnlyPostId);

        Snapshot invisible = snapshot(get(DETAIL, teamMembersOnlySlug).param("teamId", teamSlug), outsiderId);

        assertNotFound(invisible);
        verify(reactionService, never()).getReactionStatus(anyLong(), any());
        em.clear();
        assertThat(viewCount(teamMembersOnlyPostId).longValue()).isEqualTo(before.longValue());
    }

    // ─── ヘルパー ───

    /** 存在の手がかりになりうる応答の全要素（変動ヘッダーのみ除外）。 */
    private record Snapshot(int status, JsonNode body, Map<String, List<String>> headers) {
    }

    private Snapshot snapshot(MockHttpServletRequestBuilder request, Long viewer) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(request.with(user(viewer.toString())))
                .andReturn().getResponse();
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : res.getHeaderNames()) {
            if (VOLATILE_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            headers.put(name.toLowerCase(), res.getHeaders(name));
        }
        String content = res.getContentAsString();
        JsonNode body = content.isEmpty() ? null : objectMapper.readTree(content);
        return new Snapshot(res.getStatus(), body, headers);
    }

    private static void assertNotFound(Snapshot snapshot) {
        assertThat(snapshot.status()).isEqualTo(404);
        assertThat(snapshot.body()).isNotNull();
        assertThat(snapshot.body().path("error").path("code").asText()).isEqualTo("CMS_001");
    }

    private Number viewCount(Long postId) {
        return (Number) em.createNativeQuery("SELECT view_count FROM blog_posts WHERE id = :id")
                .setParameter("id", postId)
                .getSingleResult();
    }

    private BlogPostEntity savePost(Long scopeTeamId, Long scopeOrgId, Long scopeUserId, Long author,
                                    Visibility visibility, PostStatus postStatus) {
        BlogPostEntity entity = BlogPostEntity.builder()
                .teamId(scopeTeamId).organizationId(scopeOrgId).userId(scopeUserId).authorId(author)
                .title("存在オラクルの記事").slug("oracle-" + UUID.randomUUID().toString().substring(0, 12))
                .body("本文").postType(PostType.BLOG).visibility(visibility).status(postStatus)
                .readingTimeMinutes((short) 1)
                .build();
        em.persist(entity);
        em.flush();
        return entity;
    }

    private Long saveUser(String role) {
        UserEntity entity = UserEntity.builder().email("blog-oracle-" + role + "-" + key + "@example.com")
                .lastName("試練").firstName(role).displayName("存在オラクル " + role).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
