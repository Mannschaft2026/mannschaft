package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 検索履歴・保存済み検索・サジェストの受け入れテスト（CMP-261116-0722 PR-1 試練・red 先行）。
 *
 * <p>対象 AC: C14（履歴の記録条件・正規化・上限）/ C15（保存済み検索の上限と本人限定削除）/
 * C16（他人の履歴を消せない・サジェストと didYouMean に他人の語が出ない）。
 * 正本: {@code docs/features/F04.6_search.md} §4〜§5 / §7。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 履歴・保存済み検索・サジェスト（CMP-261116-0722 試練）")
class SearchHistoryContractIT extends AbstractGlobalSearchIT {

    private static final String RECENT = SEARCH + "/recent";
    private static final String SAVED = SEARCH + "/saved";
    private static final String SUGGEST = SEARCH + "/suggestions";

    private List<String> historyQueries(Long userId) {
        return inTx(() -> {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery(
                            "SELECT query FROM search_histories WHERE user_id = :u ORDER BY searched_at DESC, id DESC")
                    .setParameter("u", userId).getResultList();
            return rows.stream().map(Object::toString).toList();
        });
    }

    private Long seedHistory(Long userId, String query, LocalDateTime searchedAt) {
        return inTx(() -> {
            em.createNativeQuery("INSERT INTO search_histories (user_id, query, searched_at) VALUES (:u, :q, :t)")
                    .setParameter("u", userId).setParameter("q", query).setParameter("t", searchedAt)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        });
    }

    private JsonNode getJson(String path, Long actor) throws Exception {
        String body = http(get(path), actor).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private int saveQuery(Long actor, String name, String queryParamsJson) throws Exception {
        String payload = mapper.writeValueAsString(Map.of("name", name, "queryParams", queryParamsJson));
        return http(post(SAVED).contentType(MediaType.APPLICATION_JSON).content(payload), actor)
                .andReturn().getResponse().getStatus();
    }

    private List<Long> savedIds(Long actor) throws Exception {
        List<Long> ids = new ArrayList<>();
        getJson(SAVED, actor).path("data").forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C14
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-C14: 履歴の記録")
    class History {

        @Test
        @DisplayName("AC-C14: 400 になった検索は履歴に残らない")
        void AC_C14_400は残さない() throws Exception {
            Long me = viewer("hist-400");

            http(get(SEARCH).param("q", "a"), me).andExpect(status().isBadRequest());
            http(get(SEARCH).param("q", "x".repeat(101)), me).andExpect(status().isBadRequest());
            http(get(SEARCH).param("q", kw).param("type", "SCHEDULE"), me).andExpect(status().isBadRequest());
            http(get(SEARCH).param("q", kw).param("size", "51").param("type", "TEAM"), me)
                    .andExpect(status().isBadRequest());

            assertThat(historyQueries(me)).isEmpty();
        }

        @Test
        @DisplayName("AC-C14: 成功した検索は正規化（trim・連続/全角スペース畳み）して1件に UPSERT される")
        void AC_C14_正規化してUPSERT() throws Exception {
            Long me = viewer("hist-norm");

            search(me, "  " + kw + "   foo　bar ");
            search(me, kw + " foo bar");
            search(me, kw + "　 foo  bar");

            assertThat(historyQueries(me)).containsExactly(kw + " foo bar");
        }

        @Test
        @DisplayName("AC-C14: 51件目で最古が消え、/recent は最新20件を新しい順に返す")
        void AC_C14_上限50件と最新20件() throws Exception {
            Long me = viewer("hist-cap");
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            for (int i = 0; i < 50; i++) {
                seedHistory(me, kw + "old" + String.format("%02d", i), base.plusMinutes(i));
            }

            search(me, kw + "newest");

            List<String> queries = historyQueries(me);
            assertThat(queries).hasSize(50);
            assertThat(queries).doesNotContain(kw + "old00").contains(kw + "old01");
            assertThat(queries.get(0)).isEqualTo(kw + "newest");

            JsonNode recent = getJson(RECENT, me).path("data");
            assertThat(recent).hasSize(20);
            assertThat(recent.get(0).path("query").asText()).isEqualTo(kw + "newest");
            assertThat(recent.get(1).path("query").asText()).isEqualTo(kw + "old49");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C14（並行）: 同一ユーザー・同一語の同時検索
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 実機E2E で再現した不具合の固定（CMP-261116-0722）: 種別タブ選択中のヘッダー検索で FE が同じ q の
     * 検索を 2 本同時に送ると、履歴記録の check-then-insert が競合して一意キー違反で 500 になっていた。
     * 履歴は 1 行への原子的な UPSERT でなければならない。
     */
    @Nested
    @DisplayName("AC-C14: 並行検索でも履歴は 1 行に UPSERT される")
    class ConcurrentHistory {

        private static final int PARALLEL = 6;
        private static final int ROUNDS = 5;

        /** 同じ q の検索を {@link #PARALLEL} 本同時に投げ、各応答ステータスを返す。 */
        private List<Integer> searchConcurrently(Long actor, String q) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < PARALLEL; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return http(get(SEARCH).param("q", q), actor).andReturn().getResponse().getStatus();
                    }));
                }
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : futures) {
                    statuses.add(f.get(60, TimeUnit.SECONDS));
                }
                return statuses;
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("AC-C14: 初出の語を同時に検索しても全件 200 で、履歴は 1 行だけ")
        void AC_C14_初出語の並行検索は500にならず1行() throws Exception {
            Long me = viewer("hist-race-new");

            for (int round = 0; round < ROUNDS; round++) {
                String q = kw + "race" + round;
                assertThat(searchConcurrently(me, q)).as("round %d: 並行検索がすべて 200", round)
                        .containsOnly(200);
            }

            List<String> expected = new ArrayList<>();
            for (int round = ROUNDS - 1; round >= 0; round--) {
                expected.add(kw + "race" + round);
            }
            assertThat(historyQueries(me)).as("語ごとに 1 行（重複行なし）").containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("AC-C14: 既存の語を同時に検索すると 1 行のまま searched_at が UTC の現在時刻へ更新される")
        void AC_C14_既存語の並行検索はsearched_atを更新() throws Exception {
            Long me = viewer("hist-race-old");
            seedHistory(me, kw + "again", LocalDateTime.of(2026, 1, 1, 0, 0));

            assertThat(searchConcurrently(me, kw + "again")).containsOnly(200);

            assertThat(historyQueries(me)).containsExactly(kw + "again");
            Number driftSeconds = inTx(() -> (Number) em.createNativeQuery(
                            "SELECT ABS(TIMESTAMPDIFF(SECOND, searched_at, UTC_TIMESTAMP())) FROM search_histories"
                                    + " WHERE user_id = :u AND query = :q")
                    .setParameter("u", me).setParameter("q", kw + "again").getSingleResult());
            assertThat(driftSeconds.longValue()).as("searched_at は UTC 壁時計で格納される（JPA 経路と同じ基準）")
                    .isLessThan(Duration.ofMinutes(5).toSeconds());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C15
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-C15: 保存済み検索")
    class Saved {

        @Test
        @DisplayName("AC-C15: 10件まで保存でき、11件目は 409")
        void AC_C15_上限10件() throws Exception {
            Long me = viewer("saved-cap");
            for (int i = 0; i < 10; i++) {
                assertThat(saveQuery(me, "保存" + i, "{\"q\":\"" + kw + i + "\"}")).as("%d 件目", i + 1)
                        .isEqualTo(201);
            }
            assertThat(saveQuery(me, "保存10", "{\"q\":\"" + kw + "\"}")).isEqualTo(409);
            assertThat(savedIds(me)).hasSize(10);
        }

        @Test
        @DisplayName("AC-C15: queryParams が JSON として不正なら 400（500 にしない）")
        void AC_C15_不正JSONは400() throws Exception {
            Long me = viewer("saved-json");
            assertThat(saveQuery(me, "壊れ", "{not json")).isEqualTo(400);
            assertThat(savedIds(me)).isEmpty();
        }

        @Test
        @DisplayName("AC-C15: DELETE /saved/{id} は本人のものだけ削除でき、他人の id は 404（存在を明かさない）")
        void AC_C15_本人だけ削除() throws Exception {
            Long owner = viewer("saved-owner");
            Long stranger = viewer("saved-stranger");
            assertThat(saveQuery(owner, "本人の保存", "{\"q\":\"" + kw + "\"}")).isEqualTo(201);
            Long id = savedIds(owner).get(0);

            http(delete(SAVED + "/{id}", id), stranger).andExpect(status().isNotFound());
            http(delete(SAVED + "/{id}", Long.MAX_VALUE), stranger).andExpect(status().isNotFound());
            assertThat(savedIds(owner)).as("他人の削除要求で消えていない").containsExactly(id);

            http(delete(SAVED + "/{id}", id), owner).andExpect(status().isNoContent());
            assertThat(savedIds(owner)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C16
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-C16: 他人の検索語")
    class OthersHistory {

        @Test
        @DisplayName("AC-C16: 他人の検索履歴の id を DELETE /recent/{id} すると 404 で、履歴は残る")
        void AC_C16_他人の履歴は消せない() throws Exception {
            Long owner = viewer("hist-owner");
            Long stranger = viewer("hist-stranger");
            Long historyId = seedHistory(owner, kw + "secret", LocalDateTime.now().minusMinutes(1));

            http(delete(RECENT + "/{id}", historyId), stranger).andExpect(status().isNotFound());

            assertThat(historyQueries(owner)).containsExactly(kw + "secret");
        }

        /**
         * GlobalSearchController#suggest の自己スコープ性（KEYWORD 候補は認証主体本人の検索履歴だけ）を固定する。
         */
        @Test
        @DisplayName("AC-C16: suggestions に他人の検索語は出ず、本人の語は KEYWORD として出る")
        void AC_C16_サジェストに他人の語が出ない() throws Exception {
            Long me = viewer("sugg-me");
            Long stranger = viewer("sugg-stranger");
            seedHistory(stranger, kw + "strangerword", LocalDateTime.now().minusMinutes(1));
            seedHistory(me, kw + "myword", LocalDateTime.now().minusMinutes(1));

            JsonNode suggestions = mapper.readTree(http(get(SUGGEST).param("q", kw), me)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                    .path("data").path("suggestions");

            List<String> texts = new ArrayList<>();
            suggestions.forEach(s -> texts.add(s.path("text").asText()));
            assertThat(texts).as("本人の語は出る（対照）").contains(kw + "myword");
            assertThat(texts).as("他人の語は出ない").doesNotContain(kw + "strangerword");
            for (JsonNode s : suggestions) {
                if ((kw + "myword").equals(s.path("text").asText())) {
                    assertThat(s.path("type").asText()).isEqualTo("KEYWORD");
                }
            }
        }

        @Test
        @DisplayName("AC-C16: didYouMean は本人の履歴だけから出す（他人の近い語は出さない）")
        void AC_C16_didYouMeanに他人の語が出ない() throws Exception {
            Long me = viewer("dym-me");
            Long stranger = viewer("dym-stranger");
            seedHistory(stranger, kw + "ab", LocalDateTime.now().minusMinutes(1));

            JsonNode body = search(me, kw + "a");
            JsonNode help = body.path("data").path("zeroResultsHelp");
            assertThat(help.isObject()).as("0件なので zeroResultsHelp がある（応答: %s）", body).isTrue();
            assertThat(help.path("didYouMean").isNull()).as("他人の語 %s を候補に出さない", kw + "ab").isTrue();

            seedHistory(me, kw + "ab", LocalDateTime.now().minusMinutes(1));
            JsonNode mine = search(me, kw + "a");
            assertThat(mine.path("data").path("zeroResultsHelp").path("didYouMean").asText())
                    .as("本人の履歴なら距離2以内の語を出す（対照）").isEqualTo(kw + "ab");
        }
    }
}
