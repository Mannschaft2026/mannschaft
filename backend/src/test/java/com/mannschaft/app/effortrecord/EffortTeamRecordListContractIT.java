package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 PR-3b チーム一覧・詳細・定義候補の契約（試練 / red 先行。CMP-261115-2145）。
 * AC-41・42・43・59・70（E02・E10・E15）。
 *
 * <p>API 契約 §1・§4・§6: 一覧は {@code {data, meta{nextCursor, hasNext, limit}}}。user_id・期間・definition_uuid で絞り、
 * 非所属 403・不在チーム 404・他チームの人は空。1回の走査は最大1000行で、全件が非表示なら「空・hasNext=true・nextCursor あり」、
 * 走査が終われば「空・hasNext=false・nextCursor=null」（空ページと走査終了を区別する）。F00 の照会障害は 503 EFFORT_004 で、
 * 空一覧にしない。定義候補は VISIBLE の履歴がある定義だけで、非表示の履歴しかない定義を出さない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b チーム一覧・詳細・定義候補")
class EffortTeamRecordListContractIT extends EffortTeamShareItSupport {

    private static final LocalDate DAY1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate DAY2 = LocalDate.of(2026, 11, 2);
    private static final LocalDate DAY3 = LocalDate.of(2026, 11, 3);

    private JsonNode shareOn(Long owner, World w, Long templateId, LocalDate day, String minutes) throws Exception {
        JsonNode record = teamRecord(owner, w.team(), templateId, day, minutes, "メモ", null);
        return shareVisible(owner, w.team(), templateId, record, "時間");
    }

    private static String definitionUuid(JsonNode ownerRecord) {
        return ownerRecord.path("definition").path("definitionUuid").asText();
    }

    // ═══════════════════════════════ AC-41（E02/E10） ═══════════════════════════════

    @Test
    @DisplayName("AC-41: user_id・期間・definition_uuid で絞り込める")
    void AC41_絞り込み() throws Exception {
        World w = world("AC41絞込");
        Long other = anotherMember(w, "AC41別本人");
        Long template2 = teamTemplate(w.admin(), w.team(), "AC41別練習", shareFields());
        JsonNode a1 = shareOn(w.owner(), w, w.template(), DAY1, "10");
        JsonNode c2 = shareOn(other, w, w.template(), DAY2, "20");
        JsonNode a3 = shareOn(w.owner(), w, template2, DAY3, "30");

        assertThat(listedShareIds(w.viewer(), w.team())).as("recordedOn DESC").containsExactly(shareId(a3), shareId(c2), shareId(a1));
        assertThat(listedShareIds(w.viewer(), w.team(), "user_id", String.valueOf(w.owner())))
                .containsExactly(shareId(a3), shareId(a1));
        assertThat(listedShareIds(w.viewer(), w.team(), "date_from", DAY2.toString(), "date_to", DAY2.toString()))
                .containsExactly(shareId(c2));
        assertThat(listedShareIds(w.viewer(), w.team(), "definition_uuid", definitionUuid(a3)))
                .containsExactly(shareId(a3));
        expectError(teamList(w.viewer(), w.team(), "userId", String.valueOf(w.owner())), 400, "COMMON_001");
    }

    @Test
    @DisplayName("AC-41: 非所属は 403 COMMON_002、存在しないチームは 404、他チームの人を指定すると 200 の空（存在を推測させない）")
    void AC41_認可と他チームの人() throws Exception {
        World w = world("AC41認可");
        shareOn(w.owner(), w, w.template(), DAY1, "10");
        Long outsider = user("AC41非所属");
        Long otherTeam = team("AC41他チーム");
        member(outsider, otherTeam);

        expectError(teamList(outsider, w.team()), 403, "COMMON_002");
        assertThat(teamList(w.viewer(), Long.MAX_VALUE - 7).getStatus()).isIn(403, 404);
        JsonNode empty = expect(teamList(w.viewer(), w.team(), "user_id", String.valueOf(outsider)), 200);
        assertThat(empty.path("data")).isEmpty();
        assertThat(empty.at("/meta/hasNext").asBoolean()).isFalse();
    }

    /** F00 を通らない VISIBLE 行（owner 側の原本が無い）を recordedOn=2026-11-20 で n 行入れる。 */
    private void seedHiddenRows(Long teamId, Long ownerId, int n) {
        tx(() -> {
            em.createNativeQuery("SET SESSION cte_max_recursion_depth = 5000").executeUpdate();
            em.createNativeQuery("INSERT INTO effort_team_shares (id, team_id, owner_user_id, source_record_id, source_version, "
                            + "grant_membership_id, share_version, definition_id, recorded_on, projection_json, projection_hash, "
                            + "state, version, created_at, updated_at) "
                            + "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < :n) "
                            + "SELECT UUID_TO_BIN(UUID()), :team, :owner, UUID_TO_BIN(UUID()), 1, 1, 1, UUID_TO_BIN(UUID()), "
                            + "'2026-11-20', '{}', REPEAT('0', 64), 'VISIBLE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM seq")
                    .setParameter("n", n).setParameter("team", teamId).setParameter("owner", ownerId).executeUpdate();
            return null;
        });
    }

    @Test
    @DisplayName("AC-41/E15: 1回の走査は最大1000行。全件が非表示なら空ページで hasNext=true・nextCursor あり、走査終了は hasNext=false・nextCursor=null")
    void AC41_走査上限と空ページと走査終了の区別() throws Exception {
        World w = world("AC41走査");
        JsonNode real = shareOn(w.owner(), w, w.template(), DAY1, "10");
        seedHiddenRows(w.team(), w.owner(), 1001);

        JsonNode page1 = expect(teamList(w.viewer(), w.team(), "limit", "20"), 200);
        assertThat(page1.path("data")).as("先頭1000行は全て非表示").isEmpty();
        assertThat(page1.at("/meta/hasNext").asBoolean()).as("空でも続きがある").isTrue();
        assertThat(page1.at("/meta/nextCursor").asText(null)).isNotBlank();

        JsonNode page2 = expect(teamList(w.viewer(), w.team(), "limit", "20", "cursor", page1.at("/meta/nextCursor").asText()), 200);
        List<String> ids = new ArrayList<>();
        page2.path("data").forEach(r -> ids.add(r.path("id").asText()));
        assertThat(ids).containsExactly(shareId(real));
        assertThat(page2.at("/meta/hasNext").asBoolean()).as("走査終了").isFalse();
        assertThat(page2.at("/meta/nextCursor").isNull()).isTrue();
    }

    // ═══════════════════════════════ AC-42 ═══════════════════════════════

    @Test
    @DisplayName("AC-42: 詳細で別チームの share UUID を指定すると 404 EFFORT_001")
    void AC42_別チームのshareは404() throws Exception {
        World w = world("AC42詳細");
        World other = world("AC42別");
        member(w.viewer(), other.team());
        JsonNode inOther = shareOn(other.owner(), other, other.template(), DAY1, "10");
        expectError(teamDetail(w.viewer(), w.team(), shareId(inOther)), 404, "EFFORT_001");
        expectError(teamDetail(w.viewer(), w.team(), java.util.UUID.randomUUID().toString()), 404, "EFFORT_001");
        assertThat(sharedRecord(w.viewer(), other.team(), shareId(inOther)).path("id").asText()).isEqualTo(shareId(inOther));
    }

    @Test
    @DisplayName("AC-42: /definitions は VISIBLE 履歴のある有効定義と廃止定義（isRetired）を返し、非表示の履歴しかない定義は出さない。{shareId} より先に解決する")
    void AC42_定義候補() throws Exception {
        World w = world("AC42定義");
        Long retiredTemplate = teamTemplate(w.admin(), w.team(), "AC42廃止", shareFields());
        Long revokedOnly = teamTemplate(w.admin(), w.team(), "AC42取消のみ", shareFields());
        Long moderatedOnly = teamTemplate(w.admin(), w.team(), "AC42非表示のみ", shareFields());
        Long pendingOnly = teamTemplate(w.admin(), w.team(), "AC42同期中のみ", shareFields());

        JsonNode active = shareOn(w.owner(), w, w.template(), DAY1, "10");
        JsonNode retired = shareOn(w.owner(), w, retiredTemplate, DAY2, "20");
        retireTeamTemplate(w.admin(), retiredTemplate);
        JsonNode revoked = shareOn(w.owner(), w, revokedOnly, DAY3, "30");
        revoke(w.owner(), revoked);
        JsonNode moderated = shareOn(w.owner(), w, moderatedOnly, DAY3, "40");
        expect(moderateApi(w.admin(), w.team(), shareId(moderated), (Long) teamShareRow(shareId(moderated)).get("version"),
                java.util.UUID.randomUUID().toString()), 200);
        JsonNode pendingRecord = teamRecord(w.owner(), w.team(), pendingOnly, DAY3, "50", "メモ", null);
        failOn("effort_team_shares", "BEFORE INSERT", "NEW.team_id = " + w.team());
        JsonNode pendingPreview = preview(w.owner(), w.team(), pendingOnly, pendingRecord, "時間");
        share(w.owner(), id(pendingRecord), version(pendingRecord), pendingPreview.path("previewToken").asText());

        JsonNode body = expect(teamDefinitions(w.viewer(), w.team()), 200);
        Map<String, Boolean> candidates = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        body.path("data").forEach(d -> {
            candidates.put(d.path("definitionUuid").asText(), d.path("isRetired").asBoolean());
            names.put(d.path("definitionUuid").asText(), d.path("templateName").asText());
        });
        assertThat(candidates).containsEntry(definitionUuid(active), false).containsEntry(definitionUuid(retired), true);
        assertThat(candidates).as("非表示・同期中の履歴しかない定義は候補に出さない")
                .doesNotContainKeys(definitionUuid(revoked), definitionUuid(moderated), definitionUuid(pendingRecord));
        assertThat(names.get(definitionUuid(retired))).isEqualTo("AC42廃止");
        expectError(teamDefinitions(user("AC42非所属"), w.team()), 403, "COMMON_002");
    }

    // ═══════════════════════════════ AC-43（E10） ═══════════════════════════════

    @Test
    @DisplayName("AC-43: F00（owner 側の許可照会）が障害を起こすと一覧・詳細とも 503 EFFORT_004（空の一覧にしない）")
    void AC43_F00障害は503() throws Exception {
        World w = world("AC43");
        JsonNode visible = shareOn(w.owner(), w, w.template(), DAY1, "10");
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(shareId(visible));
        String parked = "effort_records_ac43_" + Long.toHexString(System.nanoTime());
        triggerDdl("RENAME TABLE effort_records TO " + parked);
        MockHttpServletResponse list;
        MockHttpServletResponse detail;
        try {
            list = teamList(w.viewer(), w.team());
            detail = teamDetail(w.viewer(), w.team(), shareId(visible));
        } finally {
            triggerDdl("RENAME TABLE " + parked + " TO effort_records");
        }
        expectError(list, 503, "EFFORT_004");
        expectError(detail, 503, "EFFORT_004");
    }

    // ═══════════════════════════════ AC-70（E15/E10） ═══════════════════════════════

    @Test
    @DisplayName("AC-70: meta は {nextCursor, hasNext, limit}。0件は data:[]・hasNext=false・nextCursor=null。改竄・別チームの cursor は 400")
    void AC70_metaの形とcursor() throws Exception {
        World w = world("AC70");
        JsonNode empty = expect(teamList(w.viewer(), w.team()), 200);
        assertThat(empty.path("data").isArray()).isTrue();
        assertThat(empty.path("data")).isEmpty();
        List<String> metaKeys = new ArrayList<>();
        empty.path("meta").fieldNames().forEachRemaining(metaKeys::add);
        assertThat(metaKeys).containsExactlyInAnyOrder("nextCursor", "hasNext", "limit");
        assertThat(empty.at("/meta/hasNext").asBoolean()).isFalse();
        assertThat(empty.at("/meta/nextCursor").isNull()).isTrue();
        assertThat(empty.at("/meta/limit").asInt()).isEqualTo(20);

        shareOn(w.owner(), w, w.template(), DAY1, "10");
        shareOn(w.owner(), w, w.template(), DAY2, "20");
        JsonNode first = expect(teamList(w.viewer(), w.team(), "limit", "1"), 200);
        assertThat(first.path("data")).hasSize(1);
        assertThat(first.at("/meta/hasNext").asBoolean()).isTrue();
        String cursor = first.at("/meta/nextCursor").asText();
        JsonNode second = expect(teamList(w.viewer(), w.team(), "limit", "1", "cursor", cursor), 200);
        assertThat(second.path("data")).hasSize(1);
        assertThat(second.at("/meta/hasNext").asBoolean()).isFalse();

        int middle = cursor.length() / 2;
        String tampered = cursor.substring(0, middle) + (cursor.charAt(middle) == 'A' ? 'B' : 'A') + cursor.substring(middle + 1);
        expectError(teamList(w.viewer(), w.team(), "limit", "1", "cursor", tampered), 400, "COMMON_001");
        World other = world("AC70別");
        member(w.viewer(), other.team());
        expectError(teamList(w.viewer(), other.team(), "limit", "1", "cursor", cursor), 400, "COMMON_001");
        expectError(teamList(w.viewer(), w.team(), "limit", "1", "cursor", cursor, "user_id", String.valueOf(w.owner())),
                400, "COMMON_001");
        expectError(teamList(w.viewer(), w.team(), "limit", "101"), 400, "COMMON_001");
    }

    // ═══════════════════════════════ AC-59 ═══════════════════════════════

    private record Counted(int total, Map<String, Long> byTable) {
    }

    private Counted statements(Long viewer, Long teamId, int expectedSize) throws Exception {
        try (SqlIntentCounter.CurrentThreadCapture capture = SqlIntentCounter.captureCurrentThread()) {
            JsonNode body = expect(teamList(viewer, teamId, "limit", "20"), 200);
            assertThat(body.path("data")).hasSize(expectedSize);
            Map<String, Long> byTable = new LinkedHashMap<>();
            for (String table : List.of("memberships", "users", "effort_records", "effort_team_shares")) {
                byTable.put(table, capture.capturedSqls().stream().filter(s -> s.toLowerCase().contains(table)).count());
            }
            return new Counted(capture.totalCount(), byTable);
        }
    }

    @Test
    @DisplayName("AC-59: 一覧1ページ（20件・投稿者20人）でも profile・資格 batch・F00 batch の照会は1件時から増えない")
    void AC59_一覧20件でも照会回数が増えない() throws Exception {
        World small = world("AC59一件");
        shareOn(small.owner(), small, small.template(), DAY1, "10");
        World large = world("AC59二十件");
        for (int i = 0; i < 20; i++) {
            Long author = i == 0 ? large.owner() : anotherMember(large, "AC59投稿者" + i);
            shareOn(author, large, large.template(), DAY1.plusDays(i % 5), String.valueOf(10 + i));
        }
        Counted one = statements(small.viewer(), small.team(), 1);
        Counted twenty = statements(large.viewer(), large.team(), 20);
        assertThat(twenty.total()).as("SQL 総数（1件時=" + one.total() + "）").isLessThanOrEqualTo(one.total());
        twenty.byTable().forEach((table, n) -> assertThat(n)
                .as(table + " の照会回数（1件時=" + one.byTable().get(table) + "）").isLessThanOrEqualTo(one.byTable().get(table)));
        assertThat(twenty.byTable().get("effort_records")).as("F00 の owner 照会は1ページ1回").isLessThanOrEqualTo(1);
        assertThat(twenty.total()).as("1ページの SQL 総数の上限").isLessThanOrEqualTo(15);
    }
}
