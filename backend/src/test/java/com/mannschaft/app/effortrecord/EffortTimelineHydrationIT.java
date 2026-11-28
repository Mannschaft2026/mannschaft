package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.timeline.PostScopeType;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F06.4.1 PR-3b タイムラインの努力由来カードの hydrate（試練 / red 先行。CMP-261115-2145）。
 *
 * <p>データ設計 §7: 努力由来の投稿の本文は固定の非個人識別子だけで、値は DTO 生成時に F00 を通った共有から組み立てる。
 * タイムラインの応答（{@code PostResponse}・{@code PostDetailResponse}）は、努力由来の投稿にだけ
 * {@code sourceType=PERSONAL_EFFORT_SHARE} と {@code effortShare}（チーム詳細 API と同じ SharedRecord）を付ける。
 * 通常の投稿は本文が固定識別子と同じ文字列でも {@code sourceType}・{@code effortShare} を持たない（偽装できない）。
 * 見る資格の無い人・取消済み・管理者非表示は投稿ごと出さず、purge 後は共有本文を残して著者だけを匿名にする。
 * hydrate は 1 ページにつき一括で行い、件数に比例してクエリを増やさない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b タイムラインの努力由来カードの hydrate")
class EffortTimelineHydrationIT extends EffortTeamShareItSupport {

    private static final String POSTS = TIMELINE + "/posts";
    private static final String SOURCE_TYPE = "PERSONAL_EFFORT_SHARE";
    /** 努力由来投稿の本文（TimelineEffortSourceService.EFFORT_POST_CONTENT と同じ文字列）。 */
    private static final String EFFORT_CONTENT = "effort.share";

    private static String canary(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).replaceAll("[0-9-]", "x");
    }

    private JsonNode teamFeed(Long viewer, Long teamId, String... params) throws Exception {
        var request = get(TIMELINE + "/feed").param("scopeType", "TEAM").param("scopeId", String.valueOf(teamId));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return expect(http(request, viewer), 200);
    }

    /** 一覧応答（data.posts / data.pinned / data[]）から投稿を ID で探す。無ければ null。 */
    private static JsonNode find(JsonNode body, Long postId) {
        List<JsonNode> all = new ArrayList<>();
        JsonNode data = body.path("data");
        if (data.isArray()) {
            data.forEach(all::add);
        } else {
            data.path("posts").forEach(all::add);
            data.path("pinned").forEach(all::add);
        }
        return all.stream().filter(p -> p.path("id").asLong() == postId).findFirst().orElse(null);
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static void assertNormal(String where, JsonNode post) {
        assertThat(post).as(where + ": 通常の投稿は出る").isNotNull();
        assertThat(absent(post.path("sourceType"))).as(where + ": 通常の投稿に sourceType を付けない").isTrue();
        assertThat(absent(post.path("effortShare"))).as(where + ": 通常の投稿に effortShare を付けない").isTrue();
    }

    private static void assertEffort(String where, JsonNode post, JsonNode expectedShared) {
        assertThat(post).as(where + ": 努力由来の投稿は出る").isNotNull();
        assertThat(post.path("sourceType").asText()).as(where + ": 目印").isEqualTo(SOURCE_TYPE);
        assertThat(post.path("effortShare")).as(where + ": チーム詳細 API と同じ SharedRecord").isEqualTo(expectedShared);
    }

    // ═══════════════════════════════ a. 付く経路・付かない投稿 ═══════════════════════════════

    @Test
    @DisplayName("a: TEAM TL・個人集約 TL・getUserPosts・投稿詳細で、努力由来の投稿に目印と許可済み SharedRecord（allowlist の項目だけ）が付き、通常投稿には付かない")
    void 努力由来の投稿にだけSharedRecordが付く() throws Exception {
        World w = world("HY1");
        String note = canary("zqhynote");
        String unselected = canary("zqhymemo");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", unselected, note), "科目", "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        Long normal = seedNormalPost(PostScopeType.TEAM, w.team(), w.viewer(), "通常の投稿 HY1");
        JsonNode shared = sharedRecord(w.viewer(), w.team(), sid);
        assertThat(shared.path("fields")).as("allowlist の 2 項目だけ").hasSize(2);
        List<String> canaries = List.of(note, unselected, id(visible), "grantMembershipId", "privateNote");

        JsonNode feed = teamFeed(w.viewer(), w.team());
        assertEffort("TEAM TL", find(feed, postId), shared);
        assertNormal("TEAM TL", find(feed, normal));
        assertNoCanary("TEAM TL", feed.toString(), canaries);

        for (Long viewer : List.of(w.viewer(), w.owner())) {
            JsonNode my = expect(http(get(TIMELINE + "/my"), viewer), 200);
            assertEffort("個人集約 TL", find(my, postId), shared);
            assertNormal("個人集約 TL", find(my, normal));
            assertNoCanary("個人集約 TL", my.toString(), canaries);
        }

        JsonNode user = expect(http(get(TIMELINE + "/users/{userId}/posts", w.owner()), w.viewer()), 200);
        JsonNode userPost = find(user, postId);
        if (userPost != null) {
            assertEffort("getUserPosts", userPost, shared);
        }

        JsonNode detail = expect(http(get(POSTS + "/{id}", postId), w.viewer()), 200).path("data");
        assertThat(detail.path("sourceType").asText()).as("詳細: 目印").isEqualTo(SOURCE_TYPE);
        assertThat(detail.path("effortShare")).as("詳細: SharedRecord").isEqualTo(shared);
        assertNoCanary("投稿詳細", detail.toString(), canaries);
        JsonNode normalDetail = expect(http(get(POSTS + "/{id}", normal), w.viewer()), 200).path("data");
        assertThat(absent(normalDetail.path("sourceType")) && absent(normalDetail.path("effortShare")))
                .as("詳細: 通常の投稿には付けない").isTrue();

        // 値は投稿本文へ複写しない（本文は固定識別子のまま）
        assertThat(postRow(postId).get("content")).isEqualTo(EFFORT_CONTENT);
    }

    // ═══════════════════════════════ b. 見る資格の無い人 ═══════════════════════════════

    @Test
    @DisplayName("b: 非所属・他チームの所属者・脱退済みには値も目印も出ず、投稿詳細の応答は通常の投稿と同じ（存在オラクルを作らない）")
    void 資格の無い人には値を出さない() throws Exception {
        World w = world("HY2");
        String value = canary("zqhyval");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", value, null), "科目", "時間", "メモ");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        Long normal = seedNormalPost(PostScopeType.TEAM, w.team(), w.owner(), "通常の投稿 HY2");
        assertThat(sharedRecord(w.viewer(), w.team(), sid).toString()).as("対照: 所属者には値が見える").contains(value);

        Long outsider = user("HY2部外者");
        Long otherTeam = team("HY2別チーム");
        Long otherMember = user("HY2別チーム員");
        member(otherMember, otherTeam);
        Long leaver = anotherMember(w, "HY2脱退者");
        expect(leaveTeamApi(leaver, w.team()), 204);

        List<String> canaries = List.of(value, sid, SOURCE_TYPE, "effortShare\":{");
        Map<String, Long> viewers = new LinkedHashMap<>();
        viewers.put("非所属", outsider);
        viewers.put("他チーム", otherMember);
        viewers.put("脱退済み", leaver);
        for (Map.Entry<String, Long> e : viewers.entrySet()) {
            String who = e.getKey();
            Long viewer = e.getValue();
            MockHttpServletResponse effortDetail = http(get(POSTS + "/{id}", postId), viewer);
            MockHttpServletResponse normalDetail = http(get(POSTS + "/{id}", normal), viewer);
            assertNoCanary(who + " 詳細", effortDetail.getContentAsString(), canaries);
            if (normalDetail.getStatus() == 200) {
                // 通常の投稿が見える人（公開範囲による）に対しては、努力由来は存在しない投稿と同じ 404
                MockHttpServletResponse missing = http(get(POSTS + "/{id}", Long.MAX_VALUE - 7), viewer);
                assertThat(effortDetail.getStatus()).as(who + ": 存在しない投稿と同じ").isEqualTo(missing.getStatus());
                assertThat(json(effortDetail).at("/error/code").asText())
                        .isEqualTo(json(missing).at("/error/code").asText());
            } else {
                assertThat(effortDetail.getStatus()).as(who + ": 通常の投稿と同じ応答").isEqualTo(normalDetail.getStatus());
                assertThat(json(effortDetail).at("/error/code").asText()).as(who + ": 通常の投稿と同じコード")
                        .isEqualTo(json(normalDetail).at("/error/code").asText());
            }
            MockHttpServletResponse feed = http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                    .param("scopeId", String.valueOf(w.team())), viewer);
            assertNoCanary(who + " TEAM TL", feed.getContentAsString(), canaries);
            assertNoCanary(who + " 個人集約 TL", http(get(TIMELINE + "/my"), viewer).getContentAsString(), canaries);
            assertNoCanary(who + " getUserPosts", http(get(TIMELINE + "/users/{userId}/posts", w.owner()), viewer)
                    .getContentAsString(), canaries);
        }
    }

    // ═══════════════════════════════ c. 取消・管理者非表示・purge ═══════════════════════════════

    @Test
    @DisplayName("c: 本人取消・管理者非表示の共有は TL から消え詳細は 404。purge 後は共有本文を残して著者だけを匿名で表示する")
    void 取消と管理者非表示とpurge() throws Exception {
        World w = world("HY3");
        String revokedValue = canary("zqhyrev");
        String moderatedValue = canary("zqhymod");
        String purgedValue = canary("zqhypur");
        JsonNode toRevoke = shareVisible(w, teamRecord(w.owner(), w, "11", revokedValue, null), "時間", "メモ");
        JsonNode toModerate = shareVisible(w, teamRecord(w.owner(), w, "12", moderatedValue, null), "時間", "メモ");
        Long purger = anotherMember(w, "HY3退会者");
        JsonNode toPurge = shareVisible(purger, w.team(), w.template(),
                teamRecord(purger, w, "13", purgedValue, "HY3NOTE"), "時間", "メモ");
        Long revokedPost = effortPostId(shareId(toRevoke));
        Long moderatedPost = effortPostId(shareId(toModerate));
        Long purgedPost = effortPostId(shareId(toPurge));
        JsonNode before = teamFeed(w.viewer(), w.team());
        assertThat(find(before, revokedPost)).as("対照: 取消前は見える").isNotNull();
        assertThat(find(before, moderatedPost)).as("対照: 非表示前は見える").isNotNull();

        revoke(w.owner(), toRevoke);
        String msid = shareId(toModerate);
        expect(moderateApi(w.admin(), w.team(), msid, (Long) teamShareRow(msid).get("version"),
                UUID.randomUUID().toString()), 200);

        tx(() -> {
            eventPublisher.publishEvent(new AccountPurgedEvent(purger, "hash"));
            return null;
        });
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(ownerRow(id(toPurge)).get("purged")));

        JsonNode feed = teamFeed(w.viewer(), w.team());
        JsonNode my = expect(http(get(TIMELINE + "/my"), w.viewer()), 200);
        for (JsonNode body : List.of(feed, my)) {
            assertThat(find(body, revokedPost)).as("取消済みは出ない").isNull();
            assertThat(find(body, moderatedPost)).as("管理者非表示は出ない").isNull();
            assertNoCanary("取消・非表示の値", body.toString(), List.of(revokedValue, moderatedValue));
        }
        for (Long gone : List.of(revokedPost, moderatedPost)) {
            MockHttpServletResponse detail = http(get(POSTS + "/{id}", gone), w.viewer());
            assertThat(detail.getStatus()).as("詳細は存在しない投稿と同じ 404").isEqualTo(404);
            assertNoCanary("取消・非表示の詳細", detail.getContentAsString(), List.of(revokedValue, moderatedValue));
        }

        JsonNode expected = sharedRecord(w.viewer(), w.team(), shareId(toPurge));
        JsonNode purgedCard = find(feed, purgedPost);
        assertEffort("purge 後の TEAM TL", purgedCard, expected);
        JsonNode share = purgedCard.path("effortShare");
        assertThat(share.toString()).as("共有本文は残る").contains(purgedValue);
        assertThat(share.toString()).as("私的 note は出ない").doesNotContain("HY3NOTE");
        assertThat(absent(share.path("author").path("userId"))).as("著者の userId を出さない").isTrue();
        assertThat(share.path("author").path("displayName").asText()).as("著者は匿名").doesNotContain("HY3退会者");
        JsonNode purgedDetail = expect(http(get(POSTS + "/{id}", purgedPost), w.viewer()), 200).path("data");
        assertThat(purgedDetail.path("effortShare")).isEqualTo(expected);
    }

    // ═══════════════════════════════ d. 偽装できない ═══════════════════════════════

    @Test
    @DisplayName("d: 通常投稿の本文に固定識別子と同じ文字列を書いても、目印も SharedRecord も付かない")
    void 本文の固定文字列では偽装できない() throws Exception {
        World w = world("HY4");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", EFFORT_CONTENT);
        body.put("scopeType", "TEAM");
        body.put("scopeId", String.valueOf(w.team()));
        // test プロファイルは role_permissions が空で MEMBER は投稿できないため、ADMIN が通常 API で作る（EffortTimelineGuardIT と同じ）
        JsonNode created = expect(http(post(POSTS).contentType(MediaType.APPLICATION_JSON).content(write(body)),
                w.admin()), 201).path("data");
        Long forged = created.path("id").asLong();
        assertThat(absent(created.path("sourceType")) && absent(created.path("effortShare"))).isTrue();
        Long seeded = seedNormalPost(PostScopeType.TEAM, w.team(), w.owner(), EFFORT_CONTENT);

        JsonNode feed = teamFeed(w.viewer(), w.team());
        assertNormal("TEAM TL（API で作った偽装）", find(feed, forged));
        assertNormal("TEAM TL（直接作った偽装）", find(feed, seeded));
        JsonNode my = expect(http(get(TIMELINE + "/my"), w.viewer()), 200);
        assertNormal("個人集約 TL", find(my, forged));
        JsonNode detail = expect(http(get(POSTS + "/{id}", forged), w.viewer()), 200).path("data");
        assertThat(absent(detail.path("sourceType")) && absent(detail.path("effortShare"))).as("詳細").isTrue();
    }

    // ═══════════════════════════════ e. batch（N+1 禁止） ═══════════════════════════════

    private long statements(Statistics stats, Long viewer, Long teamId) throws Exception {
        long before = stats.getPrepareStatementCount();
        JsonNode feed = teamFeed(viewer, teamId, "limit", "20");
        long used = stats.getPrepareStatementCount() - before;
        assertThat(feed.path("data").path("posts")).hasSize(20);
        return used;
    }

    @Test
    @DisplayName("e: 20 件のページで、努力由来 20 件でも 1 件でも hydrate のクエリ数は変わらない（件数に比例しない）")
    void 二十件のページでクエリ数が件数に比例しない() throws Exception {
        World one = world("HY5a");
        Long oneEffort = effortPostId(shareId(shareVisible(one, teamRecord(one.owner(), one, "1", "a", null), "時間")));
        for (int i = 0; i < 19; i++) {
            seedNormalPost(PostScopeType.TEAM, one.team(), one.owner(), "通常 " + i);
        }
        assertThat(find(teamFeed(one.viewer(), one.team(), "limit", "20"), oneEffort))
                .as("対照のページにも努力由来が 1 件入っている（hydrate が両方で走る）").isNotNull();
        World many = world("HY5b");
        List<Long> owners = List.of(many.owner(), anotherMember(many, "HY5b二"), anotherMember(many, "HY5b三"),
                anotherMember(many, "HY5b四"));
        for (int i = 0; i < 20; i++) {
            Long owner = owners.get(i % owners.size());
            shareVisible(owner, many.team(), many.template(),
                    teamRecord(owner, many, String.valueOf(i + 1), "m" + i, null), "時間", "メモ");
        }
        JsonNode manyFeed = teamFeed(many.viewer(), many.team(), "limit", "20");
        manyFeed.path("data").path("posts").forEach(p -> {
            assertThat(p.path("sourceType").asText()).isEqualTo(SOURCE_TYPE);
            assertThat(p.path("effortShare").path("fields")).hasSize(2);
        });

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            statements(stats, one.viewer(), one.team());
            statements(stats, many.viewer(), many.team());
            long oneCount = statements(stats, one.viewer(), one.team());
            long manyCount = statements(stats, many.viewer(), many.team());
            assertThat(manyCount - oneCount).as("努力由来 20 件と 1 件の差（1 件: " + oneCount + "、20 件: " + manyCount + "）")
                    .isLessThan(10);
        } finally {
            stats.setStatisticsEnabled(enabled);
        }
    }

    // ═══════════════════════════════ f. purge 後の外側の投稿者 ═══════════════════════════════

    @Test
    @DisplayName("f: purge 後に匿名の共有を載せる投稿は、外側の投稿者（author.userId・socialProfileId・user）も共有と同じ匿名表現にし、退会者の投稿一覧から辿れない")
    void purge後は外側の投稿者も共有と同じ匿名表現にする() throws Exception {
        World w = world("HY6");
        Long purger = anotherMember(w, "HY6退会者");
        JsonNode toPurge = shareVisible(purger, w.team(), w.template(),
                teamRecord(purger, w, "13", canary("zqhyanon"), null), "時間", "メモ");
        String sid = shareId(toPurge);
        Long postId = effortPostId(sid);
        JsonNode beforeMy = find(expect(http(get(TIMELINE + "/my"), w.viewer()), 200), postId);
        assertThat(beforeMy.path("author").path("userId").asLong()).as("対照: purge 前は外側に本人の ID が出る")
                .isEqualTo(purger);
        assertThat(beforeMy.path("user").path("id").asLong()).as("対照: 個人集約 TL は user を enrich する").isEqualTo(purger);

        tx(() -> {
            eventPublisher.publishEvent(new AccountPurgedEvent(purger, "hash"));
            return null;
        });
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(ownerRow(id(toPurge)).get("purged")));
        JsonNode anonymous = sharedRecord(w.viewer(), w.team(), sid).path("author");
        assertThat(absent(anonymous.path("userId"))).as("前提: 共有の著者は匿名").isTrue();

        Map<String, JsonNode> cards = new LinkedHashMap<>();
        cards.put("TEAM TL", find(teamFeed(w.viewer(), w.team()), postId));
        cards.put("個人集約 TL", find(expect(http(get(TIMELINE + "/my"), w.viewer()), 200), postId));
        cards.put("投稿詳細", expect(http(get(POSTS + "/{id}", postId), w.viewer()), 200).path("data"));
        for (Map.Entry<String, JsonNode> e : cards.entrySet()) {
            String where = e.getKey();
            JsonNode card = e.getValue();
            assertThat(card).as(where + ": 共有本文は残る").isNotNull();
            assertThat(card.path("effortShare").path("author")).as(where + ": 共有の著者は匿名").isEqualTo(anonymous);
            assertThat(absent(card.path("author").path("userId"))).as(where + ": 外側の author.userId を出さない").isTrue();
            assertThat(absent(card.path("author").path("socialProfileId"))).as(where + ": socialProfileId を出さない")
                    .isTrue();
            JsonNode user = card.path("user");
            if (!absent(user)) {
                assertThat(absent(user.path("id"))).as(where + ": user.id を出さない").isTrue();
                assertThat(user.path("displayName")).as(where + ": 表示名は共有の著者と同じ匿名表示")
                        .isEqualTo(anonymous.path("displayName"));
                assertThat(absent(user.path("avatarUrl"))).as(where + ": アバターを出さない").isTrue();
            }
            assertNoCanary(where, card.toString(), List.of("HY6退会者"));
        }
        assertThat(absent(cards.get("個人集約 TL").path("user"))).as("個人集約 TL は匿名の user を返す").isFalse();

        JsonNode userPosts = expect(http(get(TIMELINE + "/users/{userId}/posts", purger), w.viewer()), 200);
        assertThat(find(userPosts, postId)).as("退会者の ID から匿名の共有へ辿れない").isNull();
    }
}
