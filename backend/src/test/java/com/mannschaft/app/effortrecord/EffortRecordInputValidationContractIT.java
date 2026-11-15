package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 PR-1 入力境界（試練 / red 先行。CMP-261115-2145）。AC-4 / AC-5。
 *
 * <p>受理側（201）と拒否側（400 + {@code COMMON_001} + {@code fieldErrors}）を対で検査し、
 * 拒否された入力で原本が1行も作られないことを DB で確認する。境界値は親設計 §3 の semantic 表に従う
 * （DURATION 0〜1440 整数 / WEIGHT 0超〜1000 小数2桁 / INTENSITY 0〜10 整数 / SUBJECT 1〜100字 / MENU 2000字）。</p>
 *
 * <p>NUMBER semantic（0〜999999999.99）と TEXT semantic（2000字）の境界は標準プリセットに該当 field が無く、
 * TEAM 定義の作成（definitionToken、PR-2）が前提のため PR-2 の試練で扱う。数値の NaN/Infinity/指数表記の拒否は
 * NUMBER 型の DURATION / WEIGHT で検査する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 個人原本の入力境界（AC-4/5）")
class EffortRecordInputValidationContractIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    /** 各プリセットの妥当な基準値。1 field だけ差し替えて境界を検査する。 */
    private static Map<String, String> baseline(UUID definition) {
        if (definition.equals(STUDY)) {
            return values("subject", "数学", "duration", "30");
        }
        if (definition.equals(EXERCISE)) {
            return values("duration", "30", "intensity", "5", "menu", "ラン");
        }
        return values("weight", "60.50");
    }

    static Stream<Arguments> AC4_境界() {
        return Stream.of(
                // DURATION: 0〜1440 の整数分
                Arguments.of("DURATION -1", STUDY, "duration", "-1", 400),
                Arguments.of("DURATION 1441", STUDY, "duration", "1441", 400),
                Arguments.of("DURATION 1.5", STUDY, "duration", "1.5", 400),
                Arguments.of("DURATION 0", STUDY, "duration", "0", 201),
                Arguments.of("DURATION 1440", STUDY, "duration", "1440", 201),
                Arguments.of("DURATION NaN", STUDY, "duration", "NaN", 400),
                Arguments.of("DURATION Infinity", STUDY, "duration", "Infinity", 400),
                Arguments.of("DURATION 指数表記", STUDY, "duration", "1e2", 400),
                Arguments.of("DURATION 非数", STUDY, "duration", "abc", 400),
                // WEIGHT: 0超〜1000、小数2桁
                Arguments.of("WEIGHT 0", WEIGHT, "weight", "0", 400),
                Arguments.of("WEIGHT 1000.01", WEIGHT, "weight", "1000.01", 400),
                Arguments.of("WEIGHT 60.123", WEIGHT, "weight", "60.123", 400),
                Arguments.of("WEIGHT 負数", WEIGHT, "weight", "-60.00", 400),
                Arguments.of("WEIGHT 指数表記", WEIGHT, "weight", "6.05e1", 400),
                Arguments.of("WEIGHT Infinity", WEIGHT, "weight", "Infinity", 400),
                Arguments.of("WEIGHT 1000.00", WEIGHT, "weight", "1000.00", 201),
                Arguments.of("WEIGHT 0.01", WEIGHT, "weight", "0.01", 201),
                // INTENSITY: 0〜10 の整数
                Arguments.of("INTENSITY 11", EXERCISE, "intensity", "11", 400),
                Arguments.of("INTENSITY 小数", EXERCISE, "intensity", "5.5", 400),
                Arguments.of("INTENSITY -1", EXERCISE, "intensity", "-1", 400),
                Arguments.of("INTENSITY 0", EXERCISE, "intensity", "0", 201),
                Arguments.of("INTENSITY 10", EXERCISE, "intensity", "10", 201),
                // SUBJECT: 1〜100 文字
                Arguments.of("SUBJECT 101字", STUDY, "subject", "あ".repeat(101), 400),
                Arguments.of("SUBJECT 100字", STUDY, "subject", "あ".repeat(100), 201),
                // MENU: 最大 2000 文字
                Arguments.of("MENU 2001字", EXERCISE, "menu", "a".repeat(2001), 400),
                Arguments.of("MENU 2000字", EXERCISE, "menu", "a".repeat(2000), 201),
                // 必須の null / 空 / 空白のみは拒否
                Arguments.of("必須 WEIGHT null", WEIGHT, "weight", null, 400),
                Arguments.of("必須 WEIGHT 空文字", WEIGHT, "weight", "", 400),
                Arguments.of("必須 WEIGHT 空白のみ", WEIGHT, "weight", "   ", 400),
                Arguments.of("必須 EXERCISE duration null", EXERCISE, "duration", null, 400),
                Arguments.of("必須 EXERCISE duration 空白", EXERCISE, "duration", " ", 400));
    }

    @ParameterizedTest(name = "AC-4 {0} → {4}")
    @MethodSource("AC4_境界")
    @DisplayName("AC-4: semantic 境界と必須判定（拒否は400+fieldErrors、原本を作らない）")
    void AC4_入力境界(String label, UUID definition, String fieldKey, String value, int expected) throws Exception {
        Long owner = user("境界");
        Map<String, String> values = baseline(definition);
        values.put(fieldKey, value);
        MockHttpServletResponse response = postCreate(owner, createBody(definition, DAY, TOKYO, values, null),
                UUID.randomUUID().toString());

        if (expected == 201) {
            JsonNode created = expect(response, 201).path("data");
            JsonNode stored = valueOf(expect(http(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get(ME + "/{id}", created.path("id").asText()), owner), 200).path("data"), fieldKey);
            assertThat(stored.asText()).as(label + " は受理値をそのまま保持").isEqualTo(value);
            assertThat(ownerRows(owner)).isEqualTo(1);
        } else {
            JsonNode body = expectError(response, 400, "COMMON_001");
            List<String> fields = new ArrayList<>();
            body.at("/error/fieldErrors").forEach(error -> fields.add(error.path("field").asText()));
            assertThat(fields).as(label + " の fieldErrors は対象 field を指す").contains("values." + fieldKey);
            assertThat(ownerRows(owner)).as(label + " は原本を作らない").isZero();
        }
    }

    @Test
    @DisplayName("AC-4: 任意欄の空文字は null に正規化し、任意数値の null を 0 にしない")
    void AC4_任意空文字はnull正規化で任意数値nullは0にしない() throws Exception {
        Long owner = user("正規化");
        JsonNode created = expect(postCreate(owner, createBody(EXERCISE, DAY, TOKYO,
                values("duration", "30", "intensity", null, "menu", ""), null), UUID.randomUUID().toString()), 201).path("data");
        assertThat(valueOf(created, "menu").isNull()).as("任意 MENU の空文字は null").isTrue();
        assertThat(valueOf(created, "intensity").isNull()).as("任意 INTENSITY の null は 0 にしない").isTrue();

        String raw = tx(() -> String.valueOf(em.createNativeQuery(
                        "SELECT CAST(values_json AS CHAR) FROM effort_records WHERE owner_user_id = :o")
                .setParameter("o", owner).getSingleResult()));
        JsonNode stored = mapper.readTree(raw);
        assertThat(stored.path("menu").isNull()).as("永続値も null: " + raw).isTrue();
        assertThat(stored.path("intensity").isNull()).as("永続値も null: " + raw).isTrue();
    }

    // ═══════════════════════════════ AC-5 ═══════════════════════════════

    @Test
    @DisplayName("AC-5: recordedOn は 1900-01-01〜本人TZの今日（1899-12-31 と明日は400）")
    void AC5_記録日の範囲は本人TZで判定する() throws Exception {
        // UTC+14 の本人 TZ。JVM / サーバー TZ ではなく本人 TZ の「今日」を上限にする。
        String zone = "Pacific/Kiritimati";
        Long owner = user("日付境界", zone);
        LocalDate today = LocalDate.now(ZoneId.of(zone));

        expectError(postCreate(owner, createBody(WEIGHT, LocalDate.of(1899, 12, 31), zone, values("weight", "60.00"), null),
                UUID.randomUUID().toString()), 400, "COMMON_001");
        expectError(postCreate(owner, createBody(WEIGHT, today.plusDays(1), zone, values("weight", "60.00"), null),
                UUID.randomUUID().toString()), 400, "COMMON_001");
        assertThat(ownerRows(owner)).isZero();

        expect(postCreate(owner, createBody(WEIGHT, LocalDate.of(1900, 1, 1), zone, values("weight", "60.00"), null),
                UUID.randomUUID().toString()), 201);
        expect(postCreate(owner, createBody(WEIGHT, today, zone, values("weight", "60.00"), null),
                UUID.randomUUID().toString()), 201);
        assertThat(ownerRows(owner)).isEqualTo(2);
    }

    static Stream<Arguments> AC5_構造() {
        return Stream.of(
                Arguments.of("不正 IANA TZ（綴り違い）", "tz", "Asia/Tokio"),
                Arguments.of("不正 IANA TZ（架空）", "tz", "Invalid/Zone"),
                Arguments.of("未知 fieldKey", "unknown", null),
                Arguments.of("重複 fieldKey", "duplicate", null),
                Arguments.of("16 field 以上", "sixteen", null),
                Arguments.of("本文 64KiB 超", "huge", null),
                Arguments.of("note 2001字", "note2001", null));
    }

    @ParameterizedTest(name = "AC-5 {0} は400")
    @MethodSource("AC5_構造")
    @DisplayName("AC-5: 不正TZ・未知/重複 fieldKey・16 field 以上・64KiB 超・note 2001字は400で原本を作らない")
    void AC5_構造不正は400(String label, String kind, String tz) throws Exception {
        Long owner = user("構造");
        Map<String, Object> body = createBody(STUDY, DAY, tz == null ? TOKYO : tz, values("subject", "数学", "duration", "30"), null);
        switch (kind) {
            case "unknown" -> body.put("values", valueList(values("subject", "数学", "duration", "30", "unknown_key", "1")));
            case "duplicate" -> {
                List<Map<String, Object>> list = valueList(values("subject", "数学", "duration", "30"));
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("fieldKey", "duration");
                dup.put("value", "40");
                list.add(dup);
                body.put("values", list);
            }
            case "sixteen" -> {
                List<Map<String, Object>> list = valueList(values("subject", "数学", "duration", "30"));
                for (int i = 0; i < 14; i++) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("fieldKey", "extra_" + i);
                    extra.put("value", "1");
                    list.add(extra);
                }
                body.put("values", list);
            }
            case "huge" -> body.put("privateNote", "x".repeat(66 * 1024));
            case "note2001" -> body.put("privateNote", "の".repeat(2001));
            default -> { }
        }
        expectError(postCreate(owner, body, UUID.randomUUID().toString()), 400, "COMMON_001");
        assertThat(ownerRows(owner)).as(label + " は原本を作らない").isZero();
    }

    @Test
    @DisplayName("AC-5: note 2000字は受理し、UTC など正しい IANA TZ は受理する")
    void AC5_note2000字と正しいTZは受理() throws Exception {
        Long owner = user("受理");
        JsonNode created = expect(postCreate(owner, createBody(STUDY, DAY, "UTC", values("subject", "数学", "duration", "30"),
                "の".repeat(2000)), UUID.randomUUID().toString()), 201).path("data");
        assertThat(created.path("privateNote").asText()).hasSize(2000);
        assertThat(created.path("recordTimezone").asText()).isEqualTo("UTC");
    }

    private long ownerRows(Long owner) {
        return count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", owner);
    }
}
