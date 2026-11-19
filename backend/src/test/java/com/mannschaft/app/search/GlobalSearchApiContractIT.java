package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.6 グローバル検索 作り直し（CMP-261116-0722 PR-1）— コア API の受け入れテスト（試練・red 先行）。
 *
 * <p>正本: {@code docs/features/F04.6_search.md}（2026-11-17 改訂）§4。対象 AC: C1〜C9 / C11 / C12 /
 * C17（応答形）/ C18。PR-1 時点で実装される種別は USER / TEAM / ORGANIZATION のため、件数・ページングの
 * 検査はこれらの LIKE 種別で行う（POST 等の未実装種別は typeCounts が 0 でよい前提）。</p>
 *
 * <p>土台・起動方式は {@link AbstractGlobalSearchIT}（実 JWT・実 Security フィルタ・実 MySQL）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 コア API 契約（CMP-261116-0722 試練）")
class GlobalSearchApiContractIT extends AbstractGlobalSearchIT {

    private Long viewer;

    @BeforeEach
    void setUpViewer() {
        viewer = viewer("core");
    }

    private int statusOf(Map<String, String> params, Long actor) throws Exception {
        var request = get(SEARCH);
        params.forEach(request::param);
        return http(request, actor).andReturn().getResponse().getStatus();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C1〜C5: 認証と入力検証
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入力検証")
    class Validation {

        @Test
        @DisplayName("AC-C1: 未認証の GET /api/v1/search?q=ab は 401")
        void AC_C1_未認証は401() throws Exception {
            http(get(SEARCH).param("q", "ab"), null).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "q=[{0}] は 400")
        @ValueSource(strings = {"", "   ", "　　", "a", " a "})
        @DisplayName("AC-C2: q が空・空白のみ・1文字は 400（エラーコードは SEARCH_ 系）")
        void AC_C2_短すぎるqは400(String q) throws Exception {
            String body = http(get(SEARCH).param("q", q), viewer)
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(body).path("error").path("code").asText())
                    .as("SearchErrorCode のコードで返すこと").startsWith("SEARCH_");
        }

        @Test
        @DisplayName("AC-C2: q が無いのも 400")
        void AC_C2_qなしは400() throws Exception {
            http(get(SEARCH), viewer).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-C2: 2文字と100文字は 200、101文字は 400")
        void AC_C2_境界値() throws Exception {
            assertThat(statusOf(Map.of("q", "ab"), viewer)).isEqualTo(200);
            assertThat(statusOf(Map.of("q", "x".repeat(100)), viewer)).isEqualTo(200);
            String body = http(get(SEARCH).param("q", "x".repeat(101)), viewer)
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(body).path("error").path("code").asText()).startsWith("SEARCH_");
        }

        @ParameterizedTest(name = "type={0} は 400")
        @ValueSource(strings = {"SCHEDULE", "post", "Team", "POST,SCHEDULE", "schedules"})
        @DisplayName("AC-C3: type に未定義値・小文字（大文字の列挙値のみ受理）を渡すと 400")
        void AC_C3_未定義typeは400(String type) throws Exception {
            assertThat(statusOf(Map.of("q", "ab", "type", type), viewer)).isEqualTo(400);
        }

        @Test
        @DisplayName("AC-C3: カンマ区切りの複数指定は受理する")
        void AC_C3_複数typeは受理() throws Exception {
            assertThat(statusOf(Map.of("q", "ab", "type", "TEAM,ORGANIZATION"), viewer)).isEqualTo(200);
            assertThat(statusOf(Map.of("q", "ab", "type", "POST,FILE"), viewer)).isEqualTo(200);
        }

        @Test
        @DisplayName("AC-C4: scopeType 指定で scopeId 無しは 400、scopeId が非数値は 400")
        void AC_C4_scope検証() throws Exception {
            assertThat(statusOf(Map.of("q", "ab", "scopeType", "TEAM"), viewer)).isEqualTo(400);
            assertThat(statusOf(Map.of("q", "ab", "scopeType", "TEAM", "scopeId", "abc"), viewer)).isEqualTo(400);
            assertThat(statusOf(Map.of("q", "ab", "scopeType", "ORGANIZATION", "scopeId", "1"), viewer))
                    .as("正しい組み合わせは 200（対照）").isEqualTo(200);
        }

        @Test
        @DisplayName("AC-C5: size=50 は 200、size=51・size=0・page=-1 は 400")
        void AC_C5_ページ検証() throws Exception {
            assertThat(statusOf(Map.of("q", "ab", "type", "TEAM", "size", "50"), viewer)).isEqualTo(200);
            assertThat(statusOf(Map.of("q", "ab", "type", "TEAM", "size", "51"), viewer)).isEqualTo(400);
            assertThat(statusOf(Map.of("q", "ab", "type", "TEAM", "size", "0"), viewer)).isEqualTo(400);
            assertThat(statusOf(Map.of("q", "ab", "type", "TEAM", "page", "-1"), viewer)).isEqualTo(400);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C6〜C9: ビューと件数
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("概要ビュー・詳細ビュー・件数")
    class Views {

        private final List<Long> teamIds = new ArrayList<>();
        private final List<Long> orgIds = new ArrayList<>();

        /** 公開チーム 7 件・公開組織 7 件（キーワード入り）。 */
        private void seedSevenEach() {
            for (int i = 0; i < 7; i++) {
                teamIds.add(publicTeam(kw + " チーム" + i));
                orgIds.add(publicOrg(kw + " 組織" + i));
            }
        }

        @Test
        @DisplayName("AC-C6: type 未指定は概要ビュー（OVERVIEW・種別ごと最大5件・typeCounts は9種別全キー・page/size 無視）")
        void AC_C6_概要ビュー() throws Exception {
            seedSevenEach();

            JsonNode body = search(viewer, Map.of("q", kw, "page", "1", "size", "1"));

            assertThat(body.path("meta").path("viewMode").asText()).isEqualTo("OVERVIEW");
            assertThat(typeCountKeys(body)).containsExactlyInAnyOrderElementsOf(ALL_TYPES);
            assertThat(typeCount(body, "TEAM")).isEqualTo(7);
            assertThat(typeCount(body, "ORGANIZATION")).isEqualTo(7);
            for (String t : List.of("POST", "MESSAGE", "THREAD", "ARTICLE", "FILE", "USER", "ACTIVITY")) {
                assertThat(typeCount(body, t)).as("該当の無い %s は 0 を明示する", t).isZero();
            }
            assertThat(resultIds(body, "TEAM")).as("page/size を無視し種別ごと最大5件").hasSize(5);
            assertThat(resultIds(body, "ORGANIZATION")).hasSize(5);
            assertThat(resultIds(body, "TEAM")).allMatch(teamIds::contains);
            assertThat(resultIds(body, "ORGANIZATION")).allMatch(orgIds::contains);
        }

        @Test
        @DisplayName("AC-C6b: type 複数指定は指定種別だけの概要ビュー（他種別の typeCounts は 0）")
        void AC_C6b_複数指定の概要ビュー() throws Exception {
            seedSevenEach();

            JsonNode body = search(viewer, Map.of("q", kw, "type", "TEAM,USER", "page", "1", "size", "1"));

            assertThat(body.path("meta").path("viewMode").asText()).isEqualTo("OVERVIEW");
            assertThat(typeCountKeys(body)).containsExactlyInAnyOrderElementsOf(ALL_TYPES);
            assertThat(typeCount(body, "TEAM")).isEqualTo(7);
            assertThat(typeCount(body, "ORGANIZATION")).as("指定外の種別は実在しても 0").isZero();
            assertThat(resultTypes(body)).as("指定種別だけを返す").containsOnly("TEAM");
            assertThat(resultIds(body, "TEAM")).hasSize(5);
        }

        @Test
        @DisplayName("AC-C7: type 単一は詳細ビュー（total/page/size/totalPages が正しく、最終ページ超は空配列で 200）")
        void AC_C7_詳細ビュー() throws Exception {
            seedSevenEach();

            JsonNode first = search(viewer, Map.of("q", kw, "type", "TEAM", "page", "0", "size", "3"));
            JsonNode meta = first.path("meta");
            assertThat(meta.path("viewMode").asText()).isEqualTo("DETAIL");
            assertThat(meta.path("total").asLong()).isEqualTo(7);
            assertThat(meta.path("page").asInt()).isZero();
            assertThat(meta.path("size").asInt()).isEqualTo(3);
            assertThat(meta.path("totalPages").asInt()).isEqualTo(3);
            assertThat(resultIds(first, "TEAM")).hasSize(3);
            assertThat(resultTypes(first)).containsOnly("TEAM");

            JsonNode second = search(viewer, Map.of("q", kw, "type", "TEAM", "page", "1", "size", "3"));
            JsonNode last = search(viewer, Map.of("q", kw, "type", "TEAM", "page", "2", "size", "3"));
            assertThat(resultIds(last, "TEAM")).hasSize(1);
            List<Long> all = new ArrayList<>(resultIds(first, "TEAM"));
            all.addAll(resultIds(second, "TEAM"));
            all.addAll(resultIds(last, "TEAM"));
            assertThat(all).as("ページ間で重複も欠落もない").containsExactlyInAnyOrderElementsOf(teamIds);

            JsonNode beyond = search(viewer, Map.of("q", kw, "type", "TEAM", "page", "3", "size", "3"));
            assertThat(beyond.path("data").path("results").isArray()).isTrue();
            assertThat(beyond.path("data").path("results")).isEmpty();
            assertThat(beyond.path("meta").path("total").asLong()).isEqualTo(7);
        }

        @Test
        @DisplayName("AC-C8: typeCounts は頭打ちしない真の可視件数（可視11・不可視3 → 11）で、詳細ビューの meta.total と一致")
        void AC_C8_真の件数() throws Exception {
            for (int i = 0; i < 11; i++) {
                publicTeam(kw + " 可視" + i);
            }
            for (int i = 0; i < 3; i++) {
                privateTeam(kw + " 不可視" + i);
            }

            JsonNode overview = search(viewer, kw);
            JsonNode detail = search(viewer, Map.of("q", kw, "type", "TEAM", "size", "50"));

            assertThat(typeCount(overview, "TEAM")).isEqualTo(11);
            assertThat(typeCount(detail, "TEAM")).isEqualTo(11);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
            assertThat(resultIds(detail, "TEAM")).hasSize(11);
        }

        @Test
        @DisplayName("AC-C9: 該当0件は 200・results=[]・typeCounts 全0・zeroResultsHelp を返す")
        void AC_C9_ゼロ件() throws Exception {
            JsonNode body = search(viewer, kw);

            assertThat(body.path("data").path("results").isArray()).isTrue();
            assertThat(body.path("data").path("results")).isEmpty();
            assertThat(typeCountKeys(body)).containsExactlyInAnyOrderElementsOf(ALL_TYPES);
            for (String t : ALL_TYPES) {
                assertThat(typeCount(body, t)).isZero();
            }
            JsonNode help = body.path("data").path("zeroResultsHelp");
            assertThat(help.isObject()).as("zeroResultsHelp を付与する（応答: %s）", body).isTrue();
            assertThat(help.has("didYouMean")).isTrue();
            assertThat(help.path("broaderQuery").isNull()).as("単一語は broaderQuery=null").isTrue();
        }

        @Test
        @DisplayName("AC-C9: 複数語で0件なら broaderQuery に語数を1つ減らした候補を返す")
        void AC_C9_複数語のbroaderQuery() throws Exception {
            JsonNode body = search(viewer, kw + " " + kw + "zz");

            JsonNode help = body.path("data").path("zeroResultsHelp");
            assertThat(help.isObject()).isTrue();
            String broader = help.path("broaderQuery").asText(null);
            assertThat(broader).as("語数を1つ減らす").isNotNull();
            assertThat(broader.trim().split(" ")).hasSize(1);
        }

        @Test
        @DisplayName("AC-C9: 1件でもヒットすれば zeroResultsHelp は付けない（対照）")
        void AC_C9_ヒット時はzeroResultsHelpなし() throws Exception {
            publicTeam(kw + " ヒット");
            JsonNode body = search(viewer, kw);
            JsonNode help = body.path("data").path("zeroResultsHelp");
            assertThat(help.isMissingNode() || help.isNull()).isTrue();
            assertThat(typeCount(body, "TEAM")).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12: 入力のエスケープと出力のエスケープ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("エスケープ")
    class Escaping {

        @ParameterizedTest(name = "q 末尾に [{0}] を含めても 200")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11: FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならない")
        void AC_C11_演算子でも500にならない(String op) throws Exception {
            assertThat(statusOf(Map.of("q", kw + op), viewer)).isEqualTo(200);
            assertThat(statusOf(Map.of("q", op + kw + op), viewer)).isEqualTo(200);
        }

        @Test
        @DisplayName("AC-C11: LIKE の % は文字として検索される（ワイルドカードにしない）")
        void AC_C11_パーセントは文字() throws Exception {
            Long literal = publicTeam(kw + "%lit");
            Long other = publicTeam(kw + "zlit");

            JsonNode body = search(viewer, kw + "%l", "TEAM");

            assertThat(resultIds(body, "TEAM")).containsExactly(literal);
            assertThat(resultIds(body, "TEAM")).doesNotContain(other);
            assertThat(typeCount(body, "TEAM")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-C11: LIKE の _ は文字として検索される（1文字ワイルドカードにしない）")
        void AC_C11_アンダースコアは文字() throws Exception {
            Long literal = publicTeam(kw + "_q");
            publicTeam(kw + "Xq");

            JsonNode body = search(viewer, kw + "_q", "TEAM");

            assertThat(resultIds(body, "TEAM")).containsExactly(literal);
            assertThat(typeCount(body, "TEAM")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-C11: BOOLEAN 演算子も文字として検索される（+ を含む名前が + 込みでヒット）")
        void AC_C11_演算子は文字() throws Exception {
            Long plus = publicTeam(kw + "+x");
            publicTeam(kw + "x");

            JsonNode body = search(viewer, kw + "+x", "TEAM");

            assertThat(resultIds(body, "TEAM")).containsExactly(plus);
        }

        @Test
        @DisplayName("AC-C12: 名前に <script> を含んでも title はプレーン（<mark> なし）、snippet に生の <script> が出ず、highlights.title が一致位置を指す")
        void AC_C12_出力エスケープ() throws Exception {
            String name = "<script>alert(1)</script>" + upper(kw) + "部";
            Long teamId = publicTeam(name);

            JsonNode body = search(viewer, kw, "TEAM");

            JsonNode hit = null;
            for (JsonNode r : body.path("data").path("results")) {
                if (r.path("id").asLong() == teamId) {
                    hit = r;
                }
            }
            assertThat(hit).as("大文字小文字を区別せずヒットする").isNotNull();
            assertThat(hit.path("title").asText()).isEqualTo(name).doesNotContain("<mark>");
            JsonNode snippet = hit.path("snippet");
            if (!snippet.isNull() && !snippet.isMissingNode()) {
                assertThat(snippet.asText()).doesNotContain("<script>");
            }
            JsonNode range = hit.path("highlights").path("title");
            assertThat(range.isArray()).as("highlights.title に [start, end]").isTrue();
            int start = range.get(0).asInt();
            int end = range.get(1).asInt();
            assertThat(name.substring(start, end)).isEqualToIgnoringCase(kw);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C17 / AC-C18
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-C17: 旧9業務種別（schedules 等）は応答に含まれず、results は配列・typeCounts は新種別のみ")
    void AC_C17_旧種別は出ない() throws Exception {
        JsonNode body = search(viewer, kw);

        JsonNode data = body.path("data");
        assertThat(data.path("results").isArray()).as("results は種別キーの連想配列ではなく配列").isTrue();
        assertThat(data.has("counts")).as("旧 counts は無い").isFalse();
        assertThat(data.has("executionTimeMs")).isFalse();
        assertThat(typeCountKeys(body)).doesNotContainAnyElementsOf(List.of(
                "schedules", "events", "reservations", "shifts", "safetyChecks", "queues",
                "teams", "organizations", "users"));
        assertThat(data.path("timedOutTypes").isArray()).isTrue();
        assertThat(data.path("query").asText()).isEqualTo(kw);
    }

    @Test
    @DisplayName("AC-C18: 1回の検索で発行される SQL 文の数が所属スコープ数（1件と30件）で変わらない")
    void AC_C18_SQL数はスコープ数に比例しない() throws Exception {
        Long single = viewer("one-scope");
        Long many = viewer("thirty-scopes");
        Long firstTeam = privateTeam(kw + " 所属0");
        teamMember(single, firstTeam);
        teamMember(many, firstTeam);
        for (int i = 1; i < 30; i++) {
            Long teamId = privateTeam(kw + " 所属" + i);
            teamMember(many, teamId);
            Long mate = user(kw + " 仲間" + i);
            teamMember(mate, teamId);
        }

        int singleCount = Math.min(countStatements(single), countStatements(single));
        int manyCount = Math.min(countStatements(many), countStatements(many));

        assertThat(singleCount).as("計測が機能していること（Hibernate 経由の SQL を捕捉）").isPositive();
        assertThat(manyCount)
                .as("所属スコープ 30 件でも SQL 文数は 1 件時と同じ（ループ内クエリなし）。1件=%d, 30件=%d",
                        singleCount, manyCount)
                .isEqualTo(singleCount);
        // 正しく見えていることも確認（計測対象の検索が空振りしていない）
        JsonNode body = search(many, kw);
        assertThat(typeCount(body, "TEAM")).isEqualTo(30);
        assertThat(typeCount(body, "USER")).isEqualTo(29);
    }

    /**
     * 1 回の {@code GET /api/v1/search} で Hibernate が発行した SQL 文数（{@link SqlIntentCounter}）。
     * 種別ごとの並列実行スレッドの SQL も global 捕捉で数える。
     */
    private int countStatements(Long actor) throws Exception {
        String token = tokens.issueAccessToken(actor, List.of("MEMBER"));
        SqlIntentCounter.reset();
        mockMvc.perform(get(SEARCH).param("q", kw).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return SqlIntentCounter.totalCount();
    }
}
