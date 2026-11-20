package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * グローバル検索の日時の扱い（CMP-261116-0722 PR-2 Codex 検分 P2-1 / P2-2 の試練）。
 *
 * <ul>
 *   <li><b>P2-1</b>: 結果の {@code createdAt} は「起きた瞬間」。Hibernate が返すアプリ層の {@code LocalDateTime} は
 *       JST（{@code UserZoneLocalDateTimeParser#SERVER_ZONE}）の壁時計なので、JST として解釈してから瞬間へ直す。
 *       DB の UTC 格納値 {@code 00:00:00}（＝ JST 09:00）は応答でも同一瞬間 {@code 00:00Z} でなければならない。
 *       POST・TEAM・ORGANIZATION（PR-1）の全種別で確かめる。</li>
 *   <li><b>P2-2</b>: {@code dateFrom} / {@code dateTo} の日付境界は<b>閲覧者の TZ</b>（{@code users.timezone}）で作る
 *       （.claudecode.md §20 のユーザー由来 TZ の規約）。固定 UTC でも固定 JST でもないことを、
 *       Asia/Tokyo と America/Los_Angeles の 2 人の閲覧者で確かめる。</li>
 * </ul>
 *
 * <p>fixture の {@code created_at} は DB 格納基準（UTC 壁時計）の文字列リテラルで直接書く（§20「テスト・seed の
 * 文字列リテラル INSERT は UTC 基準」）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索の作成日時と期間絞り込みのタイムゾーン（CMP-261116-0722 PR-2 検分 P2-1 / P2-2）")
class GlobalSearchDateTimeZoneIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";

    @Autowired private TimelinePostRepository posts;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdPostIds = new ArrayList<>();

    @BeforeEach
    void prepare() {
        ensureFulltextIndex();
        StringBuilder sb = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            sb.append(CONSONANTS.charAt(ThreadLocalRandom.current().nextInt(CONSONANTS.length())));
        }
        kw = sb.toString();
    }

    @AfterEach
    void cleanUpPosts() {
        inTx(() -> {
            for (Long id : createdPostIds) {
                em.createNativeQuery("DELETE FROM timeline_posts WHERE id = :id").setParameter("id", id)
                        .executeUpdate();
            }
            return null;
        });
        createdPostIds.clear();
    }

    @Nested
    @DisplayName("P2-1: createdAt は JST 壁時計を瞬間へ直した値（DB の UTC 格納値と同一瞬間）")
    class CreatedAtInstant {

        /** DB の UTC 格納値 2026-03-10 00:00:00 ＝ JST 2026-03-10 09:00。 */
        private static final String STORED_UTC = "2026-03-10 00:00:00";
        private static final Instant EXPECTED = Instant.parse("2026-03-10T00:00:00Z");

        @Test
        @DisplayName("POST: JST 09:00 に作成した投稿の createdAt は 09:00+09:00（= 00:00Z）")
        void POSTのcreatedAtは同一瞬間() throws Exception {
            Long author = viewer("ca-post-author");
            Long me = viewer("ca-post-me");
            Long id = post(author, kw + " 作成日時");
            setCreatedAt("timeline_posts", id, STORED_UTC);

            JsonNode hit = hitOf(search(me, kw, "POST"), "POST", id);
            assertThat(instantOf(hit)).isEqualTo(EXPECTED);
        }

        @Test
        @DisplayName("TEAM: JST 09:00 に作成したチームの createdAt は 09:00+09:00（= 00:00Z）")
        void TEAMのcreatedAtは同一瞬間() throws Exception {
            Long me = viewer("ca-team-me");
            Long teamId = publicTeam("T-" + kw);
            setCreatedAt("teams", teamId, STORED_UTC);

            JsonNode hit = hitOf(search(me, kw, "TEAM"), "TEAM", teamId);
            assertThat(instantOf(hit)).isEqualTo(EXPECTED);
        }

        @Test
        @DisplayName("ORGANIZATION: JST 09:00 に作成した組織の createdAt は 09:00+09:00（= 00:00Z）")
        void ORGANIZATIONのcreatedAtは同一瞬間() throws Exception {
            Long me = viewer("ca-org-me");
            Long orgId = publicOrg("O-" + kw);
            setCreatedAt("organizations", orgId, STORED_UTC);

            JsonNode hit = hitOf(search(me, kw, "ORGANIZATION"), "ORGANIZATION", orgId);
            assertThat(instantOf(hit)).isEqualTo(EXPECTED);
        }
    }

    @Nested
    @DisplayName("P2-2: dateFrom / dateTo の日付境界は閲覧者の TZ で作る")
    class DateRangeInViewerZone {

        @Test
        @DisplayName("閲覧者 TZ=Asia/Tokyo: 当日 00:00〜09:00 JST の投稿は含み、翌日 00:00〜09:00 JST・前日 23:30 JST は含まない")
        void 東京の閲覧者は東京の暦日で絞る() throws Exception {
            Long author = viewer("dr-jst-author");
            Long me = viewer("dr-jst-me");
            // UTC 格納値 → JST
            Long prevDayLate = postAt(author, "2026-03-09 14:30:00");   // JST 03-09 23:30（範囲外）
            Long earlyMorning = postAt(author, "2026-03-09 15:30:00");  // JST 03-10 00:30（範囲内）
            Long beforeNine = postAt(author, "2026-03-09 23:30:00");    // JST 03-10 08:30（範囲内）
            Long lateNight = postAt(author, "2026-03-10 14:30:00");     // JST 03-10 23:30（範囲内）
            Long nextEarly = postAt(author, "2026-03-10 15:30:00");     // JST 03-11 00:30（範囲外）
            Long nextBeforeNine = postAt(author, "2026-03-10 23:30:00"); // JST 03-11 08:30（範囲外）

            JsonNode body = searchDay(me, "2026-03-10");
            assertThat(resultIds(body, "POST"))
                    .containsExactlyInAnyOrder(earlyMorning, beforeNine, lateNight)
                    .doesNotContain(prevDayLate, nextEarly, nextBeforeNine);
            assertThat(typeCount(body, "POST")).isEqualTo(3);
        }

        @Test
        @DisplayName("閲覧者 TZ=America/Los_Angeles: ロサンゼルスの暦日（PDT -07:00）で絞る（UTC 固定でも JST 固定でもない）")
        void ロサンゼルスの閲覧者はロサンゼルスの暦日で絞る() throws Exception {
            Long author = viewer("dr-la-author");
            Long me = viewer("dr-la-me");
            jdbc.update("UPDATE users SET timezone = 'America/Los_Angeles' WHERE id = ?", me);
            // 2026-03-08 に夏時間入り。2026-03-10 の LA は UTC-07:00
            Long prevDay = postAt(author, "2026-03-10 06:30:00");   // LA 03-09 23:30（範囲外）
            Long startOfDay = postAt(author, "2026-03-10 07:30:00"); // LA 03-10 00:30（範囲内）
            Long endOfDay = postAt(author, "2026-03-11 06:30:00");   // LA 03-10 23:30（範囲内）
            Long nextDay = postAt(author, "2026-03-11 07:30:00");    // LA 03-11 00:30（範囲外）

            JsonNode body = searchDay(me, "2026-03-10");
            assertThat(resultIds(body, "POST"))
                    .containsExactlyInAnyOrder(startOfDay, endOfDay)
                    .doesNotContain(prevDay, nextDay);
            assertThat(typeCount(body, "POST")).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------
    // fixture / 補助
    // ------------------------------------------------------------------

    private JsonNode searchDay(Long actor, String day) throws Exception {
        return search(actor, Map.of("q", kw, "type", "POST", "dateFrom", day, "dateTo", day));
    }

    private Long post(Long author, String content) {
        Long id = inTx(() -> posts.saveAndFlush(TimelinePostEntity.builder().scopeType(PostScopeType.PUBLIC)
                .scopeId(0L).userId(author).content(content).status(PostStatus.PUBLISHED).build()).getId());
        createdPostIds.add(id);
        return id;
    }

    private Long postAt(Long author, String storedUtc) {
        Long id = post(author, kw + " 期間 " + storedUtc);
        setCreatedAt("timeline_posts", id, storedUtc);
        return id;
    }

    /** DB 格納基準（UTC 壁時計）の文字列リテラルで created_at を上書きする。 */
    private void setCreatedAt(String table, Long id, String storedUtc) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", storedUtc, id);
    }

    private static Instant instantOf(JsonNode hit) {
        String text = hit.path("createdAt").asText();
        assertThat(text).as("createdAt が出ていること（%s）", hit).isNotBlank();
        return OffsetDateTime.parse(text).toInstant();
    }

    private JsonNode hitOf(JsonNode body, String type, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if (type.equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError(type + " id=" + id + " が結果に無い（応答: " + body + "）");
    }

    private void ensureFulltextIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'timeline_posts' "
                        + "AND index_name = 'ft_timeline_posts_content'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE timeline_posts ADD FULLTEXT INDEX ft_timeline_posts_content (content) "
                    + "WITH PARSER ngram");
        }
    }
}
