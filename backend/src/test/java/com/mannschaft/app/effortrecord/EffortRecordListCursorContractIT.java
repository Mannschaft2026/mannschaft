package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-1 個人一覧のカーソル契約とクエリ数（試練 / red 先行。CMP-261115-2145）。AC-10 / AC-59。
 *
 * <p>fixture は実 Repository で直接永続化し（createdAt・id を明示）、並び順
 * {@code recordedOn DESC, createdAt DESC, id DESC} の3キーすべてが効く配置にする。
 * cursor の24時間失効は時計を差し替える {@code EffortRecordTemporalIT} で検査する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 個人一覧カーソル・クエリ数（AC-10/59）")
class EffortRecordListCursorContractIT extends EffortRecordItSupport {

    private static final Instant T0 = Instant.parse("2026-11-05T00:00:00Z");

    /** 期待順に並べた 5 件（recordedOn DESC → createdAt DESC → id DESC）を作る。 */
    private List<String> seedOrdered(Long owner) {
        UUID low = UuidV7.generate();
        UUID high = UuidV7.generate();
        if (high.compareTo(low) < 0) {
            UUID swap = low;
            low = high;
            high = swap;
        }
        List<String> expected = new ArrayList<>();
        // 同日・同 createdAt は id DESC で並ぶ（意図的に昇順 id を先に投入する）。
        String lowId = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 3), TOKYO, values("weight", "60.10"), T0, low).toString();
        String highId = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 3), TOKYO, values("weight", "60.20"), T0, high).toString();
        // 同日・createdAt 違い。
        String later = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 3), TOKYO, values("weight", "60.30"), T0.plusSeconds(60)).toString();
        String newestDay = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 4), TOKYO, values("weight", "60.40"), T0.minusSeconds(3600)).toString();
        String oldestDay = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 1), TOKYO, values("weight", "60.50"), T0.plusSeconds(7200)).toString();
        expected.add(newestDay);
        expected.add(later);
        expected.add(highId);
        expected.add(lowId);
        expected.add(oldestDay);
        return expected;
    }

    @Test
    @DisplayName("AC-10: recordedOn DESC, createdAt DESC, id DESC で cursor が重複・欠落なく辿れる")
    void AC10_並び順とcursor走査() throws Exception {
        Long owner = user("一覧");
        List<String> expected = seedOrdered(owner);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder request = get(ME).param("limit", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode body = expect(http(request, owner), 200);
            body.path("data").forEach(r -> walked.add(r.path("id").asText()));
            JsonNode meta = body.path("meta");
            assertThat(meta.path("limit").asInt()).isEqualTo(2);
            boolean hasNext = meta.path("hasNext").asBoolean();
            assertThat(meta.has("nextCursor")).as("nextCursor は省略せず返す").isTrue();
            cursor = hasNext ? meta.path("nextCursor").asText() : null;
            if (hasNext) {
                assertThat(cursor).isNotBlank().hasSizeLessThanOrEqualTo(2048);
            } else {
                assertThat(meta.path("nextCursor").isNull()).isTrue();
            }
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(walked).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-10: 0件は data:[]・hasNext=false・nextCursor=null・既定 limit=20")
    void AC10_0件の応答形() throws Exception {
        Long owner = user("空");
        JsonNode body = expect(http(get(ME), owner), 200);
        assertThat(body.path("data").isArray()).isTrue();
        assertThat(body.path("data")).isEmpty();
        assertThat(body.at("/meta/hasNext").asBoolean()).isFalse();
        assertThat(body.path("meta").has("nextCursor") && body.at("/meta/nextCursor").isNull()).isTrue();
        assertThat(body.at("/meta/limit").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("AC-10: date_from/date_to（両端包含）と definition_uuid で絞り込める")
    void AC10_期間と定義で絞り込む() throws Exception {
        Long owner = user("絞込");
        String inRange = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 2), TOKYO, values("weight", "60.00"), T0).toString();
        String edge = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 3), TOKYO, values("weight", "60.00"), T0).toString();
        seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 4), TOKYO, values("weight", "60.00"), T0);
        String study = seedRecord(owner, STUDY, LocalDate.of(2026, 11, 2), TOKYO, values("subject", "数学", "duration", "30"), T0).toString();

        List<String> ids = ids(expect(http(get(ME).param("date_from", "2026-11-02").param("date_to", "2026-11-03")
                .param("definition_uuid", WEIGHT.toString()), owner), 200));
        assertThat(ids).containsExactly(edge, inRange);

        List<String> all = ids(expect(http(get(ME).param("date_from", "2026-11-02").param("date_to", "2026-11-02"), owner), 200));
        assertThat(all).containsExactlyInAnyOrder(inRange, study);
    }

    @Test
    @DisplayName("AC-10: limit 0/101・date_from 単独・date_to 単独・未知 query は400（limit 100 は可）")
    void AC10_不正queryは400() throws Exception {
        Long owner = user("不正");
        expectError(http(get(ME).param("limit", "0"), owner), 400, "COMMON_001");
        expectError(http(get(ME).param("limit", "101"), owner), 400, "COMMON_001");
        expectError(http(get(ME).param("date_from", "2026-11-01"), owner), 400, "COMMON_001");
        expectError(http(get(ME).param("date_to", "2026-11-01"), owner), 400, "COMMON_001");
        expectError(http(get(ME).param("foo", "bar"), owner), 400, "COMMON_001");
        expectError(http(get(ME).param("dateFrom", "2026-11-01").param("dateTo", "2026-11-02"), owner), 400, "COMMON_001");
        expect(http(get(ME).param("limit", "100"), owner), 200);
    }

    @Test
    @DisplayName("AC-10: 改竄 cursor・別 filter の cursor・別ユーザーの cursor は400")
    void AC10_不正cursorは400() throws Exception {
        Long owner = user("本人");
        Long other = user("他人");
        seedOrdered(owner);
        seedOrdered(other);

        String cursor = expect(http(get(ME).param("limit", "2"), owner), 200).at("/meta/nextCursor").asText();
        assertThat(cursor).isNotBlank();

        char last = cursor.charAt(cursor.length() / 2);
        String tampered = cursor.substring(0, cursor.length() / 2) + (last == 'A' ? 'B' : 'A')
                + cursor.substring(cursor.length() / 2 + 1);
        expectError(http(get(ME).param("limit", "2").param("cursor", tampered), owner), 400, "COMMON_001");

        expectError(http(get(ME).param("limit", "2").param("cursor", cursor)
                .param("date_from", "2026-11-01").param("date_to", "2026-11-30"), owner), 400, "COMMON_001");
        expectError(http(get(ME).param("limit", "2").param("cursor", cursor)
                .param("definition_uuid", WEIGHT.toString()), owner), 400, "COMMON_001");

        expectError(http(get(ME).param("limit", "2").param("cursor", cursor), other), 400, "COMMON_001");
        expectError(http(get(ME).param("limit", "2").param("cursor", "x".repeat(2049)), owner), 400, "COMMON_001");

        // 正しい組合せなら同じ cursor で続きが読める（拒否が cursor 一律無効化ではないことの対照）。
        assertThat(ids(expect(http(get(ME).param("limit", "2").param("cursor", cursor), owner), 200))).hasSize(2);
    }

    // ═══════════════════════════════ AC-59 ═══════════════════════════════

    @Test
    @DisplayName("AC-59: 一覧1 page（20件）の SQL 数は1件時から増えない（行ごとの照会なし）")
    void AC59_一覧20件でもSQL数が増えない() throws Exception {
        Long small = user("1件");
        Long large = user("20件");
        seedRecord(small, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "数学", "duration", "30"), T0);
        for (int i = 0; i < 20; i++) {
            UUID definition = i % 3 == 0 ? STUDY : i % 3 == 1 ? EXERCISE : WEIGHT;
            seedRecord(large, definition, LocalDate.of(2026, 10, 1).plusDays(i), TOKYO,
                    definition.equals(STUDY) ? values("subject", "数学", "duration", "30")
                            : definition.equals(EXERCISE) ? values("duration", "30", "intensity", "5", "menu", "ラン")
                            : values("weight", "60.00"),
                    T0.plusSeconds(i));
        }

        int one = statements(small, 1);
        int twenty = statements(large, 20);
        assertThat(twenty).as("20件ページの SQL 数（1件時=" + one + "）").isLessThanOrEqualTo(one);
        assertThat(twenty).as("1 page の SQL 総数の上限").isLessThanOrEqualTo(10);
    }

    private int statements(Long owner, int expectedSize) throws Exception {
        try (SqlIntentCounter.CurrentThreadCapture capture = SqlIntentCounter.captureCurrentThread()) {
            JsonNode body = expect(http(get(ME).param("limit", "20"), owner), 200);
            assertThat(body.path("data")).hasSize(expectedSize);
            long effortQueries = capture.capturedSqls().stream()
                    .filter(sql -> sql.toLowerCase().contains("effort_records")).count();
            assertThat(effortQueries).as("effort_records の走査は1 page 1回: " + capture.capturedSqls()).isLessThanOrEqualTo(1);
            return capture.totalCount();
        }
    }

    private static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("data").forEach(r -> ids.add(r.path("id").asText()));
        return ids;
    }
}
