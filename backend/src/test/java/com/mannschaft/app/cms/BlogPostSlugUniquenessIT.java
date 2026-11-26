package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.role.authorization.RolePriority;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ブログ記事 slug のスコープ内一意性の契約テスト（台帳 CMP-261123-2341）。
 *
 * <p>日本語タイトルは英数字だけ残す生成規則で「114」「e2en9」のように衝突しやすい。
 * 重複した slug が作られると slug 詳細取得（{@code findBy…AndSlug}）が複数件を返して 500 になる。
 * 作成・更新・複製のどの経路でも同一スコープ（チーム・組織・個人）の slug が一意になることを固定する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ブログ記事 slug のスコープ内一意性")
class BlogPostSlugUniquenessIT extends AbstractMySqlIntegrationTest {

    private static final String JP_TITLE = "今週の練習報告（11月第4週）";
    private static final String POSTS = "/api/v1/blog/posts";
    private static final String MY_POSTS = "/api/v1/users/me/blog/posts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgId;
    private Long adminId;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        insertRoleIfAbsent();
        teamAId = TeamOrgFixtureHelper.insertTeam(em, "slug一意チームA", "slug-uq-a-" + key);
        teamBId = TeamOrgFixtureHelper.insertTeam(em, "slug一意チームB", "slug-uq-b-" + key);
        orgId = TeamOrgFixtureHelper.insertOrganization(em, "slug一意組織", "slug-uq-o-" + key);
        adminId = insertUser("slug-uq-admin-" + key + "@example.com");
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", null, orgId);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        em.flush();
        em.clear();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId.toString(), null, List.of()));
    }

    @Test
    @DisplayName("同じ日本語タイトルを2本作成しても slug は衝突せず、どちらも slug で詳細取得できる")
    void 同じ日本語タイトルでもslugが一意で詳細取得できる() throws Exception {
        JsonNode first = create(teamBody(teamAId, JP_TITLE, null));
        JsonNode second = create(teamBody(teamAId, JP_TITLE, null));

        assertThat(second.path("content").path("slug").asText()).isNotEqualTo(first.path("content").path("slug").asText());
        for (JsonNode post : List.of(first, second)) {
            mockMvc.perform(get(POSTS + "/{slug}", post.path("content").path("slug").asText())
                            .param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("英数字だけが残る同名タイトルは -2, -3 の連番で一意になる")
    void 連番が付く() throws Exception {
        String s1 = create(teamBody(teamAId, "Weekly Report", null)).path("content").path("slug").asText();
        String s2 = create(teamBody(teamAId, "Weekly Report", null)).path("content").path("slug").asText();
        String s3 = create(teamBody(teamAId, "Weekly Report", null)).path("content").path("slug").asText();

        assertThat(s1).isEqualTo("weeklyreport");
        assertThat(s2).isEqualTo("weeklyreport-2");
        assertThat(s3).isEqualTo("weeklyreport-3");
    }

    @Test
    @DisplayName("組織・個人スコープでも同名タイトルの slug は一意")
    void 組織と個人でも一意() throws Exception {
        Map<String, Object> org = teamBody(null, JP_TITLE, null);
        org.put("organizationId", orgId.toString());
        String o1 = create(org).path("content").path("slug").asText();
        String o2 = create(org).path("content").path("slug").asText();
        assertThat(o2).isNotEqualTo(o1);

        Map<String, Object> mine = teamBody(null, JP_TITLE, null);
        String p1 = createAt(MY_POSTS, mine).path("content").path("slug").asText();
        String p2 = createAt(MY_POSTS, mine).path("content").path("slug").asText();
        assertThat(p2).isNotEqualTo(p1);
    }

    @Test
    @DisplayName("別スコープ（別チーム）では同じ slug を使える")
    void 別スコープは同じslugを許す() throws Exception {
        String a = create(teamBody(teamAId, "same-slug-title", "same-slug")).path("content").path("slug").asText();
        String b = create(teamBody(teamBId, "same-slug-title", "same-slug")).path("content").path("slug").asText();

        assertThat(a).isEqualTo("same-slug");
        assertThat(b).isEqualTo("same-slug");
    }

    @Test
    @DisplayName("slug を明示指定して重複した作成は 409（CMS_005）")
    void 明示slugの重複作成は409() throws Exception {
        create(teamBody(teamAId, "t1", "dup-slug"));

        MvcResult result = mockMvc.perform(post(POSTS).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(teamBody(teamAId, "t2", "dup-slug"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("error").path("code").asText()).isEqualTo("CMS_005");
    }

    @Test
    @DisplayName("更新で slug を同スコープの別記事と同じにすると 409、自分自身の slug のままなら成功")
    void 更新でslug重複は409() throws Exception {
        create(teamBody(teamAId, "t1", "taken-slug"));
        long otherId = create(teamBody(teamAId, "t2", "other-slug")).path("id").asLong();

        mockMvc.perform(put(POSTS + "/{id}", otherId).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody("t2", "taken-slug"))))
                .andExpect(status().isConflict());
        mockMvc.perform(put(POSTS + "/{id}", otherId).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody("t2 改", "other-slug"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("更新で自分自身の slug を大文字小文字だけ変えるのは成功し、別記事の slug の大文字違いは 409")
    void 更新で大文字小文字だけ変えるのは自分自身として扱う() throws Exception {
        create(teamBody(teamAId, "t1", "taken-case"));
        long id = create(teamBody(teamAId, "t2", "case-slug")).path("id").asLong();

        // DB の照合順序は大文字小文字を区別しないため、文字列比較で自分を除くと自分自身に一致して 409 になっていた
        MvcResult ok = mockMvc.perform(put(POSTS + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody("t2", "CASE-SLUG"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(ok.getResponse().getContentAsString())
                .path("data").path("content").path("slug").asText()).isEqualTo("CASE-SLUG");
        mockMvc.perform(put(POSTS + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody("t2", "TAKEN-CASE"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("複製を繰り返しても slug は一意")
    void 複製を繰り返しても一意() throws Exception {
        long id = create(teamBody(teamAId, JP_TITLE, null)).path("id").asLong();
        String d1 = duplicate(id).path("content").path("slug").asText();
        String d2 = duplicate(id).path("content").path("slug").asText();

        assertThat(d1).isNotEqualTo(d2);
        for (String slug : List.of(d1, d2)) {
            mockMvc.perform(get(POSTS + "/{slug}", slug).param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("論理削除済み記事の slug は再利用できる")
    void 削除済みのslugは再利用できる() throws Exception {
        long id = create(teamBody(teamAId, "t1", "reuse-slug")).path("id").asLong();
        mockMvc.perform(delete(POSTS + "/{id}", id)).andExpect(status().is2xxSuccessful());

        assertThat(create(teamBody(teamAId, "t2", "reuse-slug")).path("content").path("slug").asText())
                .isEqualTo("reuse-slug");
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> teamBody(Long teamId, String title, String slug) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (teamId != null) {
            body.put("teamId", teamId.toString());
        }
        body.put("title", title);
        body.put("body", "本文");
        if (slug != null) {
            body.put("slug", slug);
        }
        return body;
    }

    private Map<String, Object> updateBody(String title, String slug) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("body", "更新本文");
        body.put("slug", slug);
        return body;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return createAt(POSTS, body);
    }

    private JsonNode createAt(String url, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode duplicate(long id) throws Exception {
        MvcResult result = mockMvc.perform(post(POSTS + "/{id}/duplicate", id)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void insertRoleIfAbsent() {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = 'ADMIN'")
                .getSingleResult();
        if (count.longValue() == 0) {
            em.createNativeQuery("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                    + "VALUES ('ADMIN', '管理者', :priority, 0, NOW(), NOW())")
                    .setParameter("priority", RolePriority.priority("ADMIN"))
                    .executeUpdate();
        }
    }

    private Long insertUser(String email) {
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, 'slug', '一意', 'slug一意', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }
}
