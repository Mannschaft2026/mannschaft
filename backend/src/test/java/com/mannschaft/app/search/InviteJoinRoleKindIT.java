package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.NoResultException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 招待トークンでの参加が、招待ロールどおりの所属・権限を作ることの受け入れテスト（CMP-261124-0157 試練）。
 *
 * <p>不具合: 招待参加（{@code InviteAcceptanceFacade#joinByInvite} → {@code MembershipGrantService#grantRole}）が
 * 招待ロールに関係なく {@code memberships.role_kind = MEMBER} を作り、{@code user_roles} にも招待ロールの行を作っていた。
 * SUPPORTER で招待された人が正式メンバー扱いになり、メンバー限定の記事が見えていた。</p>
 *
 * <p>正（F01.2 03_business_logic.md「招待参加フロー」手順8・F00.5 §2.3）: SUPPORTER 招待は {@code memberships} に
 * SUPPORTER として入会させるだけで {@code user_roles} は作らない（MEMBER 招待は暫定で従来どおり MEMBER 行も作る）。共有招待で付与できる
 * ロールは MEMBER / SUPPORTER だけで、ADMIN / DEPUTY_ADMIN（C-1 特権）・GUEST・SYSTEM_ADMIN は発行時に 422 ROLE_009。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 DB を通す（{@link AbstractGlobalSearchIT}）。招待の発行・参加・記事詳細・
 * 全体検索をすべて HTTP で踏む。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("招待参加は招待ロールどおりの所属を作る（CMP-261124-0157）")
class InviteJoinRoleKindIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String ARTICLE = "ARTICLE";

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> articleIds = new ArrayList<>();
    private final List<Long> inviteTeamIds = new ArrayList<>();
    private final List<Long> inviteUserIds = new ArrayList<>();

    @BeforeEach
    void prepareFulltextAndKeyword() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE table_schema = DATABASE() AND table_name = 'blog_posts' AND index_name = 'ft_bp_title_body'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE blog_posts ADD FULLTEXT INDEX ft_bp_title_body (title, body) WITH PARSER ngram");
        }
        StringBuilder sb = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            sb.append(CONSONANTS.charAt(ThreadLocalRandom.current().nextInt(CONSONANTS.length())));
        }
        kw = sb.toString();
    }

    @AfterEach
    void cleanUpInviteFixtures() {
        for (Long id : articleIds) {
            jdbc.update("DELETE FROM blog_posts WHERE id = ?", id);
        }
        for (Long teamId : inviteTeamIds) {
            jdbc.update("DELETE c FROM invite_token_claims c JOIN invite_tokens t ON t.id = c.token_id WHERE t.team_id = ?",
                    teamId);
            jdbc.update("DELETE FROM invite_tokens WHERE team_id = ?", teamId);
            jdbc.update("DELETE i FROM my_scope_folder_items i JOIN my_scope_folders f ON f.id = i.folder_id "
                    + "WHERE f.scope_type = 'TEAM' AND i.scope_id = ?", teamId);
        }
        for (Long userId : inviteUserIds) {
            jdbc.update("DELETE FROM audit_logs WHERE user_id = ? OR target_user_id = ?", userId, userId);
            jdbc.update("DELETE i FROM my_scope_folder_items i JOIN my_scope_folders f ON f.id = i.folder_id "
                    + "WHERE f.user_id = ?", userId);
            jdbc.update("DELETE FROM my_scope_folders WHERE user_id = ?", userId);
        }
        articleIds.clear();
        inviteTeamIds.clear();
        inviteUserIds.clear();
    }

    // ------------------------------------------------------------------
    // 受け入れ条件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: SUPPORTER 招待で参加 → memberships は SUPPORTER・user_roles 行なし・メンバー限定記事の詳細は拒否・全体検索に出ない（応援者以上の記事は見える）")
    void supporterInvite() throws Exception {
        Fixture f = fixture();
        Long joiner = joiner("supporter");
        String token = issue(f, "SUPPORTER");

        join(token, joiner);

        assertThat(activeRoleKinds(joiner, f.teamId)).containsExactly("SUPPORTER");
        assertThat(userRoleCount(joiner, f.teamId)).as("SUPPORTER 招待は user_roles を作らない").isZero();

        int detail = detailStatus(joiner, f.teamId, f.membersOnlySlug);
        assertThat(detail).as("メンバー限定記事の詳細は SUPPORTER に拒否される（403/404）").isIn(403, 404);
        assertThat(resultIds(search(joiner, kw, ARTICLE), ARTICLE))
                .as("全体検索にメンバー限定記事が出ない（応援者以上の記事は出る）")
                .containsExactly(f.supportersArticleId);
        assertThat(typeCount(search(joiner, kw, ARTICLE), ARTICLE)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2: MEMBER 招待で参加 → memberships は MEMBER・メンバー限定記事が見える（従来どおり。user_roles の MEMBER 行も暫定で残す）")
    void memberInvite() throws Exception {
        Fixture f = fixture();
        Long joiner = joiner("member");
        String token = issue(f, "MEMBER");

        join(token, joiner);

        assertThat(activeRoleKinds(joiner, f.teamId)).containsExactly("MEMBER");
        // 暫定: 除名・ロール変更（RoleService#removeMember / changeRole）が user_roles 行を前提にしているため、MEMBER 招待は
        // 従来どおり MEMBER 行も作る（InviteRolePolicy の javadoc。所属のみへの移行は CMP-261020-1558 の解消後）。
        assertThat(userRoleNames(joiner, f.teamId)).containsExactly("MEMBER");
        assertThat(detailStatus(joiner, f.teamId, f.membersOnlySlug)).isEqualTo(200);
        assertThat(resultIds(search(joiner, kw, ARTICLE), ARTICLE))
                .containsExactlyInAnyOrder(f.membersOnlyArticleId, f.supportersArticleId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "GUEST", "SYSTEM_ADMIN"})
    @DisplayName("AC-3: 共有招待は MEMBER / SUPPORTER 以外のロールでは発行できない（422 ROLE_009・招待行を作らない）")
    void nonInvitableRolesAreRejectedAtIssue(String roleName) throws Exception {
        Fixture f = fixture();

        var response = http(post("/api/v1/teams/" + f.slug + "/invite-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":" + roleId(roleName) + ",\"expiresIn\":\"7d\",\"maxUses\":null}"), f.adminId)
                .andReturn().getResponse();

        assertThat(response.getStatus()).as("発行応答: %s", response.getContentAsString()).isEqualTo(422);
        assertThat(mapper.readTree(response.getContentAsString()).path("error").path("code").asText())
                .isEqualTo("ROLE_009");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE team_id = ?", Long.class, f.teamId))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GUEST", "SYSTEM_ADMIN"})
    @DisplayName("AC-4: 発行済みの GUEST / SYSTEM_ADMIN 招待（旧データ）で参加しても付与しない（422 ROLE_009・在籍も権限も作らない）")
    void legacyNonInvitableTokensDoNotGrant(String roleName) throws Exception {
        Fixture f = fixture();
        Long joiner = joiner("legacy-" + roleName);
        String token = issue(f, "MEMBER");
        jdbc.update("UPDATE invite_tokens SET role_id = ? WHERE team_id = ?", roleId(roleName), f.teamId);

        var response = http(post("/api/v1/invite/" + token + "/join"), joiner).andReturn().getResponse();

        assertThat(response.getStatus()).as("参加応答: %s", response.getContentAsString()).isEqualTo(422);
        assertThat(activeRoleKinds(joiner, f.teamId)).isEmpty();
        assertThat(userRoleCount(joiner, f.teamId)).isZero();
        assertThat(jdbc.queryForObject("SELECT used_count FROM invite_tokens WHERE team_id = ?", Integer.class, f.teamId))
                .isZero();
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private record Fixture(Long teamId, String slug, Long adminId, Long membersOnlyArticleId, String membersOnlySlug,
                           Long supportersArticleId) { }

    /** 公開チーム（チーム自体は誰でも見える）＋ ADMIN ＋ メンバー限定記事・応援者以上記事。 */
    private Fixture fixture() {
        Long teamId = publicTeam("招待ロール-" + kw);
        inviteTeamIds.add(teamId);
        String slug = jdbc.queryForObject("SELECT slug FROM teams WHERE id = ?", String.class, teamId);
        Long adminId = viewer("invite-admin");
        inviteUserIds.add(adminId);
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            return null;
        });
        String membersSlug = "inv-m-" + UUID.randomUUID().toString().substring(0, 12);
        Long members = article(teamId, adminId, Visibility.MEMBERS_ONLY, kw + " メンバー限定", membersSlug);
        Long supporters = article(teamId, adminId, Visibility.SUPPORTERS_AND_ABOVE, kw + " 応援者以上",
                "inv-s-" + UUID.randomUUID().toString().substring(0, 12));
        return new Fixture(teamId, slug, adminId, members, membersSlug, supporters);
    }

    private Long joiner(String label) {
        Long id = viewer("invite-" + label);
        inviteUserIds.add(id);
        return id;
    }

    private Long article(Long teamId, Long authorId, Visibility visibility, String title, String slug) {
        Long id = inTx(() -> {
            BlogPostEntity post = BlogPostEntity.builder().teamId(teamId).authorId(authorId).title(title).slug(slug)
                    .body("本文").postType(PostType.BLOG).visibility(visibility).status(PostStatus.PUBLISHED)
                    .publishedAt(LocalDateTime.now().minusDays(1)).build();
            em.persist(post);
            em.flush();
            return post.getId();
        });
        articleIds.add(id);
        return id;
    }

    private Long roleId(String name) {
        return inTx(() -> {
            try {
                return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :n").setParameter("n", name)
                        .getSingleResult()).longValue();
            } catch (NoResultException e) {
                em.createNativeQuery("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:n, :n, :p, 0, NOW(), NOW())")
                        .setParameter("n", name).setParameter("p", RolePriority.priority(name)).executeUpdate();
                return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :n").setParameter("n", name)
                        .getSingleResult()).longValue();
            }
        });
    }

    /** ADMIN が HTTP で共有招待を発行し、平文トークンを返す（201 を確かめる）。 */
    private String issue(Fixture f, String roleName) throws Exception {
        var response = http(post("/api/v1/teams/" + f.slug + "/invite-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":" + roleId(roleName) + ",\"expiresIn\":\"7d\",\"maxUses\":null}"), f.adminId)
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("発行応答: %s", response.getContentAsString()).isEqualTo(201);
        JsonNode body = mapper.readTree(response.getContentAsString());
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void join(String token, Long userId) throws Exception {
        var response = http(post("/api/v1/invite/" + token + "/join"), userId).andReturn().getResponse();
        assertThat(response.getStatus()).as("参加応答: %s", response.getContentAsString()).isEqualTo(200);
    }

    private int detailStatus(Long viewer, Long teamId, String slug) throws Exception {
        return http(get("/api/v1/blog/posts/" + slug).param("teamId", String.valueOf(teamId)), viewer)
                .andReturn().getResponse().getStatus();
    }

    private List<String> activeRoleKinds(Long userId, Long teamId) {
        return jdbc.queryForList("SELECT role_kind FROM memberships WHERE user_id = ? AND scope_type = 'TEAM' "
                + "AND scope_id = ? AND left_at IS NULL", String.class, userId, teamId);
    }

    private List<String> userRoleNames(Long userId, Long teamId) {
        return jdbc.queryForList("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.user_id = ? AND ur.team_id = ?", String.class, userId, teamId);
    }

    private long userRoleCount(Long userId, Long teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND team_id = ?", Long.class,
                userId, teamId);
    }
}
