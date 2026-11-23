package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.timeline.PostScopeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F06.4.1 PR-3b タイムラインのガードと読み取り経路（試練 / red 先行。CMP-261115-2145）。AC-39・AC-40・AC-66（E03・E06・E16）。
 *
 * <p>データ設計 §7.2: 努力由来の投稿（timeline_effort_sources に行がある投稿）への通常操作は、操作を問わず
 * 403 EFFORT_003（fieldErrors なし）。通常の投稿は従来どおり操作できる（同じ fixture の対照で確かめる）。
 * 読み取りは F00 を通ったときだけで、全体検索は SQL で努力由来を常に除外し、件数とページ境界からも漏らさない。</p>
 *
 * <p>努力由来の投稿は {@link #seedEffortPost} で直接作る（対応表の share_uuid はどの共有にも一致しないため F00 を通らない）。
 * 共有の後処理の実装に依存させず、各 BE 入口のガードだけを検査する。</p>
 *
 * <p>「公開化」: 努力由来の投稿を公開へ切り替える専用の BE 入口は現存しないため、PUBLIC スコープへの repost の拒否と、
 * share_with_friends が false のまま変わらないことの確認で代替する。「リンク展開」の BE 機能も現存しないので、
 * 投稿詳細 GET で代替する。フレンドチーム転送は、フレンド関係の判定より先に EFFORT_003 を返す前提で検査する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b タイムラインのガードと読み取り経路")
class EffortTimelineGuardIT extends EffortTeamShareItSupport {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String POSTS = TIMELINE + "/posts";

    @Autowired private TeamFriendRepository teamFriends;

    private String kw;
    private World w;
    private Long effortPost;
    private Long normalPost;

    @BeforeEach
    void 努力由来と通常の投稿を用意する() {
        StringBuilder sb = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            sb.append(CONSONANTS.charAt(ThreadLocalRandom.current().nextInt(CONSONANTS.length())));
        }
        kw = sb.toString();
        w = world("ガード");
        effortPost = seedEffortPost(w.team(), w.owner(), kw + " effort");
        normalPost = seedNormalPost(PostScopeType.TEAM, w.team(), w.owner(), kw + " normal");
    }

    private static void assertEffort003(MockHttpServletResponse response, String operation) throws Exception {
        assertThat(response.getStatus()).as(operation + ": " + response.getContentAsString()).isEqualTo(403);
        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.at("/error/code").asText()).as(operation).isEqualTo("EFFORT_003");
        JsonNode fieldErrors = body.at("/error/fieldErrors");
        assertThat(fieldErrors.isMissingNode() || fieldErrors.isNull() || fieldErrors.isEmpty())
                .as(operation + ": fieldErrors を付けない").isTrue();
    }

    private Map<String, Object> postBody(String content, String scopeType, Long scopeId, Long parentId, Long repostOfId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId == null ? null : String.valueOf(scopeId));
        body.put("parentId", parentId);
        body.put("repostOfId", repostOfId);
        return body;
    }

    private MockHttpServletResponse createPost(Long actor, Map<String, Object> body) throws Exception {
        return http(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(write(body)), actor);
    }

    private long posts(Long teamId) {
        return count("SELECT COUNT(*) FROM timeline_posts WHERE deleted_at IS NULL AND (scope_type = 'TEAM' AND scope_id = ?1 "
                + "OR scope_type = 'PUBLIC' AND user_id IN (SELECT user_id FROM memberships WHERE scope_type = 'TEAM' "
                + "AND scope_id = ?1))", teamId);
    }

    // ═══════════════════════════════ AC-39（E06） ═══════════════════════════════

    @Nested
    @DisplayName("AC-39: 努力由来の投稿への反応・返信・再投稿・引用・本文更新・転送・公開化は 403 EFFORT_003")
    class Interactions {

        @Test
        @DisplayName("AC-39: いいね（みたよ）とその解除は 403 EFFORT_003。通常の投稿は 201 / 200（対照を先に確かめる）")
        void AC39_いいねと解除() throws Exception {
            expect(http(post(POSTS + "/{id}/reactions", normalPost), w.viewer()), 201);
            expect(http(delete(POSTS + "/{id}/reactions", normalPost), w.viewer()), 200);
            assertEffort003(http(post(POSTS + "/{id}/reactions", effortPost), w.viewer()), "like");
            assertEffort003(http(delete(POSTS + "/{id}/reactions", effortPost), w.viewer()), "unlike");
            assertThat(postRow(effortPost).get("reactionCount")).isEqualTo(0);
            assertThat(count("SELECT COUNT(*) FROM timeline_post_reactions WHERE timeline_post_id = ?1", effortPost)).isZero();
        }

        @Test
        @DisplayName("AC-39: parentId による返信は 403 EFFORT_003（返信を作らない）。通常の投稿への返信は 201（対照を先に確かめる）")
        void AC39_返信() throws Exception {
            expect(createPost(w.admin(), postBody("返信", "TEAM", w.team(), normalPost, null)), 201);
            long before = posts(w.team());
            assertEffort003(createPost(w.admin(), postBody("返信", "TEAM", w.team(), effortPost, null)), "reply");
            assertThat(posts(w.team())).isEqualTo(before);
            assertThat(postRow(effortPost).get("replyCount")).isEqualTo(0);
        }

        @Test
        @DisplayName("AC-39: repost・引用（本文付き repost）・公開スコープへの repost は 403 EFFORT_003。通常の投稿の repost は 201（対照を先に確かめる）")
        void AC39_再投稿と引用と公開化() throws Exception {
            expect(createPost(w.admin(), postBody("再投稿", "TEAM", w.team(), null, normalPost)), 201);
            long before = posts(w.team());
            assertEffort003(createPost(w.admin(), postBody("再投稿", "TEAM", w.team(), null, effortPost)), "repost");
            assertEffort003(createPost(w.admin(), postBody("引用コメント " + kw, "TEAM", w.team(), null, effortPost)), "quote");
            assertEffort003(createPost(w.admin(), postBody("公開へ", "PUBLIC", null, null, effortPost)), "publish");
            assertThat(posts(w.team())).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-39: 投稿者本人の updatePost（本文更新）は 403 EFFORT_003（本文は変わらない）。通常の投稿は 200（対照を先に確かめる）")
        void AC39_本文更新() throws Exception {
            // MEMBER の本文更新は MANAGE_POSTS を要するため、編集権限のある ADMIN を投稿者にする（ガードは権限より先に効く）
            Long ownEffort = seedEffortPost(w.team(), w.admin(), kw + " admin effort");
            Long ownNormal = seedNormalPost(PostScopeType.TEAM, w.team(), w.admin(), kw + " admin normal");
            expect(http(patch(POSTS + "/{id}", ownNormal).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"書き換え\"}"), w.admin()), 200);
            String before = (String) postRow(ownEffort).get("content");
            assertEffort003(http(patch(POSTS + "/{id}", ownEffort).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"書き換え\"}"), w.admin()), "updatePost");
            assertThat(postRow(ownEffort).get("content")).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-39: フレンドチームへの転送は 403 EFFORT_003（転送投稿を作らない。フレンド関係の有無より先に拒否する）")
        void AC39_転送() throws Exception {
            long before = posts(w.team());
            assertEffort003(http(post("/api/v1/teams/{id}/friend-feed/{postId}/forward", w.team(), effortPost)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"MEMBER\"}"), w.admin()), "forward");
            assertThat(posts(w.team())).isEqualTo(before);
            assertThat(postRow(effortPost).get("shareWithFriends")).isEqualTo(false);
        }
    }

    // ═══════════════════════════ AC-66（E16 通常の削除・pin・bookmark） ═══════════════════════════

    @Nested
    @DisplayName("AC-66: 努力由来の投稿の通常 deletePost・pin・bookmark は 403 EFFORT_003")
    class DeletePinBookmark {

        @Test
        @DisplayName("AC-66: 投稿者本人・チーム ADMIN の deletePost は 403 EFFORT_003（投稿は残る）。通常の投稿は 204（対照を先に確かめる）")
        void AC66_deletePost() throws Exception {
            expect(http(delete(POSTS + "/{id}", normalPost), w.owner()), 204);
            assertEffort003(http(delete(POSTS + "/{id}", effortPost), w.owner()), "deletePost(本人)");
            assertEffort003(http(delete(POSTS + "/{id}", effortPost), w.admin()), "deletePost(ADMIN)");
            assertThat(postRow(effortPost).get("deleted")).isEqualTo(false);
        }

        @Test
        @DisplayName("AC-66: pin は 403 EFFORT_003（ピン留めしない）。通常の投稿は 200（対照を先に確かめる）")
        void AC66_pin() throws Exception {
            expect(http(post(POSTS + "/{id}/pin", normalPost).param("pinned", "true"), w.admin()), 200);
            assertEffort003(http(post(POSTS + "/{id}/pin", effortPost).param("pinned", "true"), w.admin()), "pin");
            assertThat(postRow(effortPost).get("pinned")).isEqualTo(false);
        }

        @Test
        @DisplayName("AC-66: bookmark は 403 EFFORT_003（行を作らない）。通常の投稿は成功する（対照を先に確かめる）")
        void AC66_bookmark() throws Exception {
            MockHttpServletResponse normal = http(post(TIMELINE + "/bookmarks/{postId}", normalPost), w.viewer());
            assertThat(normal.getStatus()).as(normal.getContentAsString()).isEqualTo(201);
            assertEffort003(http(post(TIMELINE + "/bookmarks/{postId}", effortPost), w.viewer()), "bookmark");
            assertThat(count("SELECT COUNT(*) FROM timeline_bookmarks WHERE timeline_post_id = ?1", effortPost)).isZero();
        }
    }

    // ═══════════════════════════════ AC-40（E06/E03） ═══════════════════════════════

    private static List<Long> postIds(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        JsonNode data = body.path("data");
        if (data.isArray()) {
            data.forEach(p -> ids.add(p.path("id").asLong()));
        } else {
            data.path("posts").forEach(p -> ids.add(p.path("id").asLong()));
            data.path("pinned").forEach(p -> ids.add(p.path("id").asLong()));
        }
        return ids;
    }

    @Nested
    @DisplayName("AC-40: F00 を通らない努力由来の投稿は、どの読み取り経路にも出ない")
    class ReadPaths {

        @Test
        @DisplayName("AC-40: TEAM TL・公開 TL・自分のフィード・getUserPosts・投稿詳細に出ない（通常の投稿は出る）")
        void AC40_フィードと詳細() throws Exception {
            Long publicEffort = seedEffortPost(w.team(), w.owner(), kw + " public effort");
            tx(() -> em.createNativeQuery("UPDATE timeline_posts SET scope_type = 'PUBLIC', scope_id = 0 WHERE id = :id")
                    .setParameter("id", publicEffort).executeUpdate());

            List<Long> team = postIds(expect(http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                    .param("scopeId", String.valueOf(w.team())), w.viewer()), 200));
            assertThat(team).contains(normalPost).doesNotContain(effortPost);
            List<Long> pub = postIds(expect(http(get(TIMELINE + "/feed").param("scopeType", "PUBLIC"), w.viewer()), 200));
            assertThat(pub).doesNotContain(publicEffort);
            List<Long> mine = postIds(expect(http(get(TIMELINE + "/my"), w.viewer()), 200));
            assertThat(mine).contains(normalPost).doesNotContain(effortPost);
            List<Long> user = postIds(expect(http(get(TIMELINE + "/users/{userId}/posts", w.owner()), w.viewer()), 200));
            assertThat(user).contains(normalPost).doesNotContain(effortPost, publicEffort);
            assertThat(http(get(POSTS + "/{id}", effortPost), w.viewer()).getStatus()).as("詳細は非表示").isEqualTo(404);
            expect(http(get(POSTS + "/{id}", normalPost), w.viewer()), 200);
        }

        @Test
        @DisplayName("AC-40: getReplies・getPinnedPosts に出ない（通常の返信・ピン留めは出る）")
        void AC40_返信とピン留め() throws Exception {
            Long effortReply = seedEffortPost(w.team(), w.owner(), kw + " effort reply");
            Long normalReply = seedNormalPost(PostScopeType.TEAM, w.team(), w.viewer(), kw + " normal reply");
            tx(() -> em.createNativeQuery("UPDATE timeline_posts SET parent_id = :p WHERE id IN (:ids)")
                    .setParameter("p", normalPost).setParameter("ids", List.of(effortReply, normalReply)).executeUpdate());
            tx(() -> em.createNativeQuery("UPDATE timeline_posts SET is_pinned = TRUE WHERE id IN (:ids)")
                    .setParameter("ids", List.of(effortPost, normalPost)).executeUpdate());

            List<Long> replies = postIds(expect(http(get(POSTS + "/{id}/replies", normalPost), w.viewer()), 200));
            assertThat(replies).contains(normalReply).doesNotContain(effortReply);
            List<Long> pinned = postIds(expect(http(get(TIMELINE + "/pinned").param("scopeType", "TEAM")
                    .param("scopeId", String.valueOf(w.team())), w.viewer()), 200));
            assertThat(pinned).contains(normalPost).doesNotContain(effortPost);
        }

        private void ensureFulltextIndex() {
            long index = count("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE table_schema = DATABASE() "
                    + "AND table_name = 'timeline_posts' AND index_name = 'ft_timeline_posts_content'");
            if (index == 0) {
                tx(() -> em.createNativeQuery("ALTER TABLE timeline_posts ADD FULLTEXT INDEX ft_timeline_posts_content (content) "
                        + "WITH PARSER ngram").executeUpdate());
            }
        }

        @Test
        @DisplayName("AC-40: フレンドフィード（管理者フィード）に share_with_friends=true の努力由来の投稿が出ない（通常の共有投稿は出る）")
        void AC40_フレンドフィード() throws Exception {
            Long friendTeam = team("ガードのフレンド先");
            Long friendAdmin = user("フレンド先管理者");
            teamAdmin(friendAdmin, friendTeam);
            tx(() -> teamFriends.save(TeamFriendEntity.builder().teamAId(Math.min(w.team(), friendTeam))
                    .teamBId(Math.max(w.team(), friendTeam)).establishedAt(java.time.LocalDateTime.now())
                    .aFollowId(0L).bFollowId(0L).build()));
            tx(() -> em.createNativeQuery("UPDATE timeline_posts SET share_with_friends = TRUE WHERE id IN (:ids)")
                    .setParameter("ids", List.of(effortPost, normalPost)).executeUpdate());

            // test プロファイルは role_permissions が空のため、ADMIN へ MANAGE_FRIEND_TEAMS を与える（既存の
            // FriendContentForwardExistenceOracleContractIT と同じ作法）。この試験が足した行だけを後で消す。
            List<Runnable> undo = grantToAdmin("MANAGE_FRIEND_TEAMS");
            JsonNode feed;
            try {
                feed = expect(http(get("/api/v1/teams/{teamId}/friend-feed", friendTeam), friendAdmin), 200);
            } finally {
                undo.forEach(Runnable::run);
            }
            List<Long> ids = new ArrayList<>();
            feed.path("data").forEach(p -> ids.add(p.path("postId").asLong()));
            assertThat(ids).contains(normalPost).doesNotContain(effortPost);
        }

        /** roles.ADMIN に permission を付ける。足した行（permission・role_permissions）を消す処理を返す。 */
        private List<Runnable> grantToAdmin(String permission) {
            List<Runnable> undo = new ArrayList<>();
            Long roleId = count("SELECT id FROM roles WHERE name = 'ADMIN'");
            if (count("SELECT COUNT(*) FROM permissions WHERE name = ?1", permission) == 0) {
                tx(() -> em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                        + "VALUES (:n, :n, 'TEAM', NOW(), NOW())").setParameter("n", permission).executeUpdate());
                undo.add(() -> tx(() -> em.createNativeQuery("DELETE FROM permissions WHERE name = :n")
                        .setParameter("n", permission).executeUpdate()));
            }
            Long permissionId = count("SELECT id FROM permissions WHERE name = ?1", permission);
            if (count("SELECT COUNT(*) FROM role_permissions WHERE role_id = ?1 AND permission_id = ?2", roleId, permissionId) == 0) {
                tx(() -> em.createNativeQuery("INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                        + "VALUES (:r, :p, 1, NOW())").setParameter("r", roleId).setParameter("p", permissionId).executeUpdate());
                undo.add(0, () -> tx(() -> em.createNativeQuery("DELETE FROM role_permissions WHERE role_id = :r AND permission_id = :p")
                        .setParameter("r", roleId).setParameter("p", permissionId).executeUpdate()));
            }
            return undo;
        }

        @Test
        @DisplayName("AC-40: searchPosts（timeline 検索）に出ない")
        void AC40_timeline検索() throws Exception {
            ensureFulltextIndex();
            List<Long> found = postIds(expect(http(get(TIMELINE + "/search").param("q", kw), w.viewer()), 200));
            assertThat(found).contains(normalPost).doesNotContain(effortPost);
        }

        @Test
        @DisplayName("AC-40: 全体検索は努力由来を SQL で除外し、件数（typeCounts・meta.total）とページ境界からも漏らさない")
        void AC40_全体検索の件数とページ境界() throws Exception {
            ensureFulltextIndex();
            Long normal2 = seedNormalPost(PostScopeType.TEAM, w.team(), w.viewer(), kw + " normal second");
            for (int i = 0; i < 3; i++) {
                seedEffortPost(w.team(), w.owner(), kw + " effort extra " + i);
            }
            JsonNode overview = expect(http(get("/api/v1/search").param("q", kw), w.viewer()), 200);
            assertThat(overview.at("/data/typeCounts/POST").asLong()).as("件数に努力由来を数えない").isEqualTo(2);

            JsonNode all = expect(http(get("/api/v1/search").param("q", kw).param("type", "POST").param("size", "50"),
                    w.viewer()), 200);
            assertThat(all.at("/meta/total").asLong()).isEqualTo(2);
            List<Long> ids = new ArrayList<>();
            all.at("/data/results").forEach(r -> ids.add(r.path("id").asLong()));
            assertThat(ids).containsExactlyInAnyOrder(normalPost, normal2);

            List<Long> paged = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode body = expect(http(get("/api/v1/search").param("q", kw).param("type", "POST")
                        .param("size", "1").param("page", String.valueOf(page)), w.viewer()), 200);
                assertThat(body.at("/meta/total").asLong()).as("どのページでも件数は同じ").isEqualTo(2);
                body.at("/data/results").forEach(r -> paged.add(r.path("id").asLong()));
            }
            assertThat(paged).as("ページ境界でも努力由来に枠を取られない").containsExactlyInAnyOrder(normalPost, normal2);
        }
    }
}
