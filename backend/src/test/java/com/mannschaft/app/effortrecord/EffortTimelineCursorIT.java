package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelineEffortSourceEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-3b タイムラインの続きの目印（nextCursor）に、閲覧者に見せない投稿の ID を出さない（CMP-261115-2145・案D）。
 *
 * <p>非表示の投稿（取消済み共有・F00 を通らない努力由来・退会者の匿名共有のユーザー別一覧）は飛ばし、足りない分は
 * 続きから読み足して見える投稿でページを埋める。nextCursor は常に返したページで最後に見えた投稿の ID。
 * 末尾が非表示だけなら hasNext=false・nextCursor=null。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b タイムラインの続きの目印に非表示の投稿 ID を出さない")
class EffortTimelineCursorIT extends EffortTeamShareItSupport {

    private JsonNode page(World w, int size, Long cursor) throws Exception {
        var request = get(TIMELINE + "/feed").param("scopeType", "TEAM").param("scopeId", String.valueOf(w.team()))
                .param("size", String.valueOf(size));
        if (cursor != null) {
            request.param("cursor", String.valueOf(cursor));
        }
        return expect(http(request, w.viewer()), 200);
    }

    private static List<Long> ids(JsonNode body) {
        List<Long> ids = new ArrayList<>();
        body.path("data").path("posts").forEach(p -> ids.add(p.path("id").asLong()));
        return ids;
    }

    private static Long cursorOf(JsonNode body) {
        JsonNode c = body.path("meta").path("nextCursor");
        return c.isMissingNode() || c.isNull() ? null : c.asLong();
    }

    private static boolean hasNext(JsonNode body) {
        return body.path("meta").path("hasNext").asBoolean();
    }

    private Long normal(World w, String label) {
        return seedNormalPost(PostScopeType.TEAM, w.team(), w.owner(), label);
    }

    private Long hidden(World w, String label) {
        return seedEffortPost(w.team(), w.owner(), label);
    }

    @Test
    @DisplayName("b: 取消済み共有・F00 を通らない投稿がページ末尾に来ても nextCursor に出ず、見える投稿でページが埋まる")
    void b_非表示がページ末尾に来てもカーソルに出ない() throws Exception {
        World w = world("CUR-B");
        Long n1 = normal(w, "n1");
        Long n2 = normal(w, "n2");
        JsonNode toRevoke = shareVisible(w, teamRecord(w.owner(), w, "11", "zqcurb" + UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9-]", "x"), null), "時間", "メモ");
        Long revoked = effortPostId(shareId(toRevoke));
        Long n3 = normal(w, "n3");
        Long n4 = normal(w, "n4");
        revoke(w.owner(), toRevoke);
        // 新しい順: n4, n3, (取消済み), n2, n1。size=3 の生のページ末尾は取消済み
        JsonNode first = page(w, 3, null);
        assertThat(ids(first)).as("見える投稿で埋まる").containsExactly(n4, n3, n2);
        assertThat(cursorOf(first)).as("最後に見えた投稿").isEqualTo(n2).isNotEqualTo(revoked);
        assertThat(hasNext(first)).isTrue();
        JsonNode second = page(w, 3, cursorOf(first));
        assertThat(ids(second)).containsExactly(n1);
        assertThat(hasNext(second)).isFalse();
        assertThat(cursorOf(second)).isNull();

        Long h = hidden(w, "hidden-tail");
        Long n5 = normal(w, "n5");
        JsonNode again = page(w, 2, null);
        assertThat(ids(again)).containsExactly(n5, n4);
        assertThat(cursorOf(again)).isEqualTo(n4).isNotEqualTo(h);
    }

    @Test
    @DisplayName("c: 非表示が size を超えて連続しても、見える投稿でページが埋まり、続きを辿ると欠けも重複もなく全部得られる")
    void c_非表示が連続しても全部辿れる() throws Exception {
        World w = world("CUR-C");
        List<Long> visible = new ArrayList<>();
        Set<Long> hiddenIds = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            visible.add(normal(w, "v" + i));
            for (int j = 0; j < 7; j++) {
                hiddenIds.add(hidden(w, "h" + i + "_" + j));
            }
        }
        List<Long> expected = new ArrayList<>(visible);
        java.util.Collections.reverse(expected);
        List<Long> got = new ArrayList<>();
        Long cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            JsonNode body = page(w, 2, cursor);
            got.addAll(ids(body));
            Long next = cursorOf(body);
            if (next != null) {
                assertThat(next).as("カーソルは見える投稿の ID").isIn(visible);
                assertThat(next).isNotIn(hiddenIds);
                assertThat(next).as("カーソルは前進する").isNotEqualTo(cursor);
                assertThat(hasNext(body)).isTrue();
                assertThat(ids(body)).hasSize(2);
            } else {
                assertThat(hasNext(body)).isFalse();
                break;
            }
            cursor = next;
        }
        assertThat(got).as("欠けも重複もない").containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("d: 末尾が非表示だけのとき hasNext=false・nextCursor=null。ちょうど size 件の境界も正しい")
    void d_末尾が非表示だけ() throws Exception {
        World w = world("CUR-D");
        hidden(w, "h1");
        hidden(w, "h2");
        Long n1 = normal(w, "n1");
        Long n2 = normal(w, "n2");
        Long n3 = normal(w, "n3");
        JsonNode exact = page(w, 3, null);
        assertThat(ids(exact)).containsExactly(n3, n2, n1);
        assertThat(hasNext(exact)).as("残りは非表示だけ").isFalse();
        assertThat(cursorOf(exact)).isNull();

        JsonNode two = page(w, 2, null);
        assertThat(ids(two)).containsExactly(n3, n2);
        assertThat(cursorOf(two)).isEqualTo(n2);
        assertThat(hasNext(two)).isTrue();
        JsonNode rest = page(w, 2, cursorOf(two));
        assertThat(ids(rest)).containsExactly(n1);
        assertThat(hasNext(rest)).isFalse();
        assertThat(cursorOf(rest)).isNull();
    }

    @Test
    @DisplayName("e: 0 件・非表示だけ・size 件ぴったり（非表示なし）の境界")
    void e_ゼロ件と境界() throws Exception {
        World w = world("CUR-E");
        JsonNode empty = page(w, 3, null);
        assertThat(ids(empty)).isEmpty();
        assertThat(hasNext(empty)).isFalse();
        assertThat(cursorOf(empty)).isNull();

        hidden(w, "h1");
        hidden(w, "h2");
        hidden(w, "h3");
        hidden(w, "h4");
        JsonNode onlyHidden = page(w, 3, null);
        assertThat(ids(onlyHidden)).isEmpty();
        assertThat(hasNext(onlyHidden)).isFalse();
        assertThat(cursorOf(onlyHidden)).isNull();

        World w2 = world("CUR-E2");
        Long a = normal(w2, "a");
        Long b = normal(w2, "b");
        Long c = normal(w2, "c");
        JsonNode exact = page(w2, 3, null);
        assertThat(ids(exact)).containsExactly(c, b, a);
        assertThat(hasNext(exact)).isFalse();
        assertThat(cursorOf(exact)).isNull();
        Long d = normal(w2, "d");
        JsonNode over = page(w2, 3, null);
        assertThat(ids(over)).containsExactly(d, c, b);
        assertThat(cursorOf(over)).isEqualTo(b);
        assertThat(hasNext(over)).isTrue();
    }

    @Test
    @DisplayName("a: 退会者の匿名共有はユーザー別一覧から外れ、後続の見える投稿で件数が埋まる。匿名共有の ID を続きの目印として返さない")
    void a_匿名共有の除外と補充() throws Exception {
        World w = world("CUR-A");
        Long purger = anotherMember(w, "CURA退会者");
        Long n1 = seedNormalPost(PostScopeType.TEAM, w.team(), purger, "n1");
        Long n2 = seedNormalPost(PostScopeType.TEAM, w.team(), purger, "n2");
        JsonNode toPurge = shareVisible(purger, w.team(), w.template(),
                teamRecord(purger, w, "13", "zqcura" + UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9-]", "x"), null),
                "時間", "メモ");
        Long anonymousPost = effortPostId(shareId(toPurge));
        Long n3 = seedNormalPost(PostScopeType.TEAM, w.team(), purger, "n3");
        tx(() -> {
            eventPublisher.publishEvent(new AccountPurgedEvent(purger, "hash"));
            return null;
        });
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(ownerRow(id(toPurge)).get("purged")));
        // purge 後は author の紐付けが消えるため、通常の投稿の作者は別ユーザーに寄せて並びを固定する
        JsonNode userPosts = expect(http(get(TIMELINE + "/users/{userId}/posts", w.owner()).param("size", "20"), w.viewer()), 200);
        assertThat(userPosts.path("meta").isMissingNode()).as("ユーザー別一覧は続きの目印を返さない").isTrue();
        assertThat(userPosts.toString()).doesNotContain("\"nextCursor\"");
        JsonNode feed = page(w, 3, null);
        assertThat(ids(feed)).as("チーム TL では匿名共有が作者伏せで見える").contains(anonymousPost);
        assertThat(cursorOf(feed)).isNotNull();
        // 退会者の投稿一覧は匿名共有を含めず、続きの目印も出さない
        JsonNode purgerPosts = expect(http(get(TIMELINE + "/users/{userId}/posts", purger).param("size", "2"), w.viewer()), 200);
        List<Long> got = new ArrayList<>();
        purgerPosts.path("data").forEach(p -> got.add(p.path("id").asLong()));
        assertThat(got).doesNotContain(anonymousPost);
        assertThat(purgerPosts.toString()).doesNotContain("nextCursor");
    }

    /** F00 を通らない努力由来の投稿を 1 TX でまとめて作る（走査上限の試験用。古い順に並んだ投稿 ID を返す）。 */
    private List<Long> hiddenBulk(World w, int count) {
        return tx(() -> {
            List<Long> created = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Long postId = timelinePosts.save(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM)
                        .scopeId(w.team()).userId(w.owner()).content("bulk-hidden-" + i).status(PostStatus.PUBLISHED)
                        .build()).getId();
                effortSources.save(TimelineEffortSourceEntity.builder().teamId(w.team()).timelinePostId(postId)
                        .shareUuid(UUID.randomUUID()).grantMembershipId(1L).shareVersion(1L).contentRevision(1L)
                        .isRevoked(false).build());
                created.add(postId);
            }
            timelinePosts.flush();
            effortSources.flush();
            return created;
        });
    }

    @Test
    @DisplayName("f: 非表示が走査上限（1000 行）を超えて連続しても 1 回の走査で打ち切り、非表示 ID を推定できない継続位置で続きを返し、辿ると見える投稿が欠けも重複もなく得られる")
    void f_走査上限を超える非表示の連続() throws Exception {
        World w = world("CUR-F");
        Long oldest = normal(w, "oldest");
        List<Long> hiddenIds = hiddenBulk(w, 2100);
        Long newest = normal(w, "newest");
        long minHidden = hiddenIds.get(0);
        long maxHidden = hiddenIds.get(hiddenIds.size() - 1);
        Set<Long> hiddenSet = new HashSet<>(hiddenIds);

        List<Long> got = new ArrayList<>();
        List<JsonNode> pages = new ArrayList<>();
        Set<Long> seenCursors = new HashSet<>();
        Long cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            JsonNode body = page(w, 1, cursor);
            pages.add(body);
            got.addAll(ids(body));
            Long next = cursorOf(body);
            // (B) 続きの目印にも投稿一覧にも非表示の投稿の ID を出さない。値から ID を推定できない（ID の近傍でもない）
            assertThat(ids(body)).doesNotContainAnyElementsOf(hiddenSet);
            if (next == null) {
                assertThat(hasNext(body)).isFalse();
                break;
            }
            assertThat(hasNext(body)).isTrue();
            assertThat(next).as("非表示 ID ではない").isNotIn(hiddenSet);
            assertThat(-next).as("符号反転しても非表示 ID ではない").isNotIn(hiddenSet);
            if (next < 0) {
                long magnitude = Math.abs(next);
                assertThat(magnitude < minHidden - 100_000L || magnitude > maxHidden + 100_000L)
                        .as("継続位置は非表示 ID の範囲から推定できない").isTrue();
                assertThat(Math.abs(next)).as("JS の安全な整数に収まる").isLessThanOrEqualTo(9_007_199_254_740_991L);
            } else {
                assertThat(next).as("正の目印は見えた投稿の ID").isIn(got);
            }
            // (C) 同じ目印を二度返さない（空ページの無限ループを作らない）
            assertThat(seenCursors.add(next)).as("目印は前進する").isTrue();
            cursor = next;
        }
        // (C) 辿り切ると見える投稿が欠けも重複もなく得られる
        assertThat(got).containsExactly(newest, oldest);
        assertThat(cursorOf(pages.get(pages.size() - 1))).isNull();
        // (A) 1 回の走査は 1000 行で打ち切る: 非表示 2100 行を挟むため、最古の見える投稿は 1 回目では届かず、
        // 非表示だけの区間では空ページ＋hasNext=true（暗号化した継続位置）が返る
        assertThat(ids(pages.get(0))).containsExactly(newest);
        assertThat(cursorOf(pages.get(0))).as("上限で打ち切った続きは継続位置（負の数）").isNegative();
        assertThat(pages).as("非表示だけの区間で空ページを挟む").anySatisfy(p -> {
            assertThat(ids(p)).isEmpty();
            assertThat(hasNext(p)).isTrue();
        });
        assertThat(pages.size()).as("走査量は 1 回 1000 行まで: 2100 行は 3 回以上に分かれる").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("g: 改ざん・範囲外の継続位置は 400 COMMON_001（任意の位置へ飛ばさない）")
    void g_不正な継続位置() throws Exception {
        World w = world("CUR-G");
        normal(w, "n1");
        var request = get(TIMELINE + "/feed").param("scopeType", "TEAM").param("scopeId", String.valueOf(w.team()))
                .param("size", "1").param("cursor", String.valueOf(-(1L << 53)));
        expect(http(request, w.viewer()), 400);
    }
}
