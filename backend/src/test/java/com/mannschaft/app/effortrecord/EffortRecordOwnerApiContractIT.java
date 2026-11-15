package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-1 個人 API の契約・認可・永続化（試練 / red 先行。CMP-261115-2145）。
 *
 * <p>対象 AC: AC-1（プリセット）/ AC-2（作成と再読込）/ AC-3（private 秘匿 404・未認証 401）/
 * AC-6（PUT 全置換・version）/ AC-7（note のみ編集の contentRevision）/ AC-8（削除）。</p>
 *
 * <p><b>自己スコープ契約の証跡</b>（{@code SelfScopedEndpointMarkerGuardTest} のトレーサビリティ・リンク）:
 * 本クラスは {@code EffortRecordMeController} の {@code listPresets} / {@code create} / {@code list} /
 * {@code stats} / {@code get} / {@code update} / {@code delete} が認証主体の記録だけを対象とし、
 * 他人・チーム管理者・SYSTEM_ADMIN の UUID 直打ちを {@code EFFORT_001}（404）で秘匿することを固定する。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL を通し、書込は commit して別リクエストで再読込する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 個人 API 契約（AC-1/2/3/6/7/8）")
class EffortRecordOwnerApiContractIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final Set<String> OWNER_RECORD_KEYS = Set.of("id", "version", "contentRevision", "recordedOn",
            "recordTimezone", "definition", "values", "privateNote", "share", "createdAt", "updatedAt");

    // ═══════════════════════════════ AC-1 ═══════════════════════════════

    @Test
    @DisplayName("AC-1: 所属0件でも presets は固定UUIDの標準3種と labelKey を返す")
    void AC1_所属0件でpresetsが標準3種を返す() throws Exception {
        Long lonely = user("所属なし");
        JsonNode data = expect(http(get(ME + "/presets"), lonely), 200).path("data");

        assertThat(data.isArray()).isTrue();
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        data.forEach(definition -> byCode.put(definition.path("presetCode").asText(), definition));
        assertThat(byCode.keySet()).containsExactlyInAnyOrder("STUDY_V1", "EXERCISE_V1", "WEIGHT_V1");
        assertThat(byCode.get("STUDY_V1").path("definitionUuid").asText()).isEqualTo(STUDY.toString());
        assertThat(byCode.get("EXERCISE_V1").path("definitionUuid").asText()).isEqualTo(EXERCISE.toString());
        assertThat(byCode.get("WEIGHT_V1").path("definitionUuid").asText()).isEqualTo(WEIGHT.toString());

        for (JsonNode definition : data) {
            assertThat(definition.path("source").asText()).isEqualTo("PRESET");
            assertThat(definition.path("revision").asInt()).isEqualTo(1);
            assertThat(definition.has("teamTemplateId") && definition.path("teamTemplateId").isNull())
                    .as("nullable 項目は省略せず null（API 契約 §1）").isTrue();
            assertThat(definition.path("fields").size()).isPositive();
            for (JsonNode field : definition.path("fields")) {
                assertThat(field.path("labelKey").isTextual())
                        .as("標準プリセットの labelKey は非 null（6言語表示キー）: " + field).isTrue();
                assertThat(field.path("fieldKey").asText()).matches("[a-z][a-z0-9_]{0,63}");
            }
        }

        // 親設計 §3 の semantic 規則。§6.1 固定例（科目未指定・時間 null の記録）が成立するよう STUDY は両方任意。
        JsonNode study = byCode.get("STUDY_V1");
        assertThat(study.path("durationFieldKey").asText()).isEqualTo("duration");
        assertThat(study.path("subjectFieldKey").asText()).isEqualTo("subject");
        assertThat(field(study, "subject").path("semantic").asText()).isEqualTo("SUBJECT");
        assertThat(field(study, "subject").path("required").asBoolean())
                .as("§6.1 の『科目未指定=60分』を記録できるよう科目は任意").isFalse();
        assertThat(field(study, "duration").path("required").asBoolean())
                .as("§6.1 の『科目A=null』を記録できるよう時間は任意").isFalse();
        assertThat(field(study, "duration").path("min").asText()).isEqualTo("0");
        assertThat(field(study, "duration").path("max").asText()).isEqualTo("1440");

        JsonNode exercise = byCode.get("EXERCISE_V1");
        assertThat(exercise.path("subjectFieldKey").isNull()).isTrue();
        assertThat(field(exercise, "intensity").path("min").asText()).as("INTENSITY は 0〜10（親設計 §3）").isEqualTo("0");
        assertThat(field(exercise, "intensity").path("max").asText()).isEqualTo("10");
        assertThat(field(exercise, "menu").path("semantic").asText()).isEqualTo("MENU");

        JsonNode weight = byCode.get("WEIGHT_V1");
        assertThat(field(weight, "weight").path("semantic").asText()).isEqualTo("WEIGHT");
        assertThat(field(weight, "weight").path("scale").asInt()).isEqualTo(2);
        assertThat(field(weight, "weight").path("max").asText()).as("WEIGHT は 1000 以下（AC-4: 1000.00 可）").isEqualTo("1000.00");
    }

    // ═══════════════════════════════ AC-2 ═══════════════════════════════

    @Test
    @DisplayName("AC-2: PRESET で作成すると201・version=0・contentRevision=1・share=null、詳細と一覧で同値")
    void AC2_PRESET作成は201で詳細一覧と同値() throws Exception {
        Long owner = user("本人");
        JsonNode created = expect(postCreate(owner, createBody(STUDY, DAY, TOKYO,
                values("subject", "数学", "duration", "30"), "私的メモ"), UUID.randomUUID().toString()), 201).path("data");

        assertThat(fieldNames(created)).as("OwnerRecord のキー（null も省略しない）").isEqualTo(OWNER_RECORD_KEYS);
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("contentRevision").asLong()).isEqualTo(1);
        assertThat(created.path("share").isNull()).isTrue();
        assertThat(created.path("recordedOn").asText()).isEqualTo("2026-11-01");
        assertThat(created.path("recordTimezone").asText()).isEqualTo(TOKYO);
        assertThat(created.path("privateNote").asText()).isEqualTo("私的メモ");
        assertThat(created.path("definition").path("definitionUuid").asText()).isEqualTo(STUDY.toString());
        assertThat(created.path("definition").path("revision").asInt()).isEqualTo(1);
        assertThat(valueOf(created, "subject").asText()).isEqualTo("数学");
        assertThat(valueOf(created, "duration").asText()).isEqualTo("30");
        assertThat(created.path("createdAt").asText()).as("Instant 由来 ISO8601 UTC").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");

        String id = created.path("id").asText();
        assertThat(UUID.fromString(id).version()).as("原本 id は UUIDv7").isEqualTo(7);

        JsonNode detail = expect(http(get(ME + "/{id}", id), owner), 200).path("data");
        assertThat(detail).isEqualTo(created);

        JsonNode list = expect(http(get(ME), owner), 200);
        assertThat(list.path("data")).hasSize(1);
        assertThat(list.path("data").get(0)).isEqualTo(created);

        // 永続化の再読込（別TX）でも同値。
        EffortRowView row = tx(() -> {
            Object[] r = (Object[]) em.createNativeQuery("SELECT version, content_revision, share_id, recorded_on, record_timezone "
                    + "FROM effort_records WHERE id = UNHEX(REPLACE(:id,'-',''))").setParameter("id", id).getSingleResult();
            return new EffortRowView(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), r[2], String.valueOf(r[3]), String.valueOf(r[4]));
        });
        assertThat(row.version()).isZero();
        assertThat(row.contentRevision()).isEqualTo(1);
        assertThat(row.shareId()).isNull();
        assertThat(row.recordedOn()).isEqualTo("2026-11-01");
        assertThat(row.timezone()).isEqualTo(TOKYO);
    }

    private record EffortRowView(long version, long contentRevision, Object shareId, String recordedOn, String timezone) { }

    // ═══════════════════════════════ AC-3 ═══════════════════════════════

    @Test
    @DisplayName("AC-3: 他人・チーム管理者・SYSTEM_ADMIN の GET/PUT/DELETE は不在と区別できない404 EFFORT_001")
    void AC3_他人と管理者とSYSTEM_ADMINは404で不在と区別不能() throws Exception {
        Long owner = user("本人");
        Long stranger = user("他人");
        Long teamAdminId = user("チーム管理者");
        Long sysAdmin = systemAdmin("運営");
        Long teamId = team("頑張りチーム");
        member(owner, teamId);
        teamAdmin(teamAdminId, teamId);

        JsonNode created = create(owner, EXERCISE, DAY, values("duration", "45", "intensity", "7", "menu", "秘密メニュー"), "秘密メモ");
        String id = created.path("id").asText();
        String absent = UUID.randomUUID().toString();

        // 不在 UUID に対する本人の応答（比較の基準）。
        JsonNode absentGet = expectError(http(get(ME + "/{id}", absent), owner), 404, "EFFORT_001");

        for (Long intruder : List.of(stranger, teamAdminId, sysAdmin)) {
            MockHttpServletResponse got = http(get(ME + "/{id}", id), intruder);
            JsonNode body = expectError(got, 404, "EFFORT_001");
            assertThat(body).as("他人の実在 UUID と不在 UUID で本文が一致（存在オラクルなし）").isEqualTo(absentGet);
            assertThat(got.getContentAsString()).doesNotContain("秘密メニュー").doesNotContain("秘密メモ");

            expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                    .content(write(updateBody(0, DAY, values("duration", "1", "intensity", "1", "menu", "改ざん"), null))), intruder),
                    404, "EFFORT_001");
            expectError(http(delete(ME + "/{id}", id).param("version", "0")
                    .header("Idempotency-Key", UUID.randomUUID().toString()), intruder), 404, "EFFORT_001");
        }

        // 本人には無傷のまま見える（越境要求で改変・削除されていない）。
        JsonNode after = expect(http(get(ME + "/{id}", id), owner), 200).path("data");
        assertThat(after.path("version").asLong()).isZero();
        assertThat(valueOf(after, "menu").asText()).isEqualTo("秘密メニュー");
        // 他人の一覧には混入しない。
        assertThat(expect(http(get(ME), stranger), 200).path("data")).isEmpty();
    }

    @Test
    @DisplayName("AC-3: 未認証は個人 API 全本で401")
    void AC3_未認証は401() throws Exception {
        String id = UUID.randomUUID().toString();
        String body = write(createBody(STUDY, DAY, TOKYO, values("subject", "数学", "duration", "30"), null));
        List<MockHttpServletResponse> responses = List.of(
                http(get(ME + "/presets"), null),
                http(post(ME).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", UUID.randomUUID().toString()), null),
                http(get(ME), null),
                http(get(ME + "/stats").param("date_from", "2026-11-01").param("date_to", "2026-11-07")
                        .param("definition_uuid", STUDY.toString()).param("field_key", "duration").param("chart", "BAR"), null),
                http(get(ME + "/{id}", id), null),
                http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(write(updateBody(0, DAY, values("subject", "数学", "duration", "30"), null))), null),
                http(delete(ME + "/{id}", id).param("version", "0").header("Idempotency-Key", UUID.randomUUID().toString()), null),
                http(post(ME + "/definition-snapshots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"teamTemplateId\":1}"), null));
        for (MockHttpServletResponse response : responses) {
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(401);
        }
    }

    // ═══════════════════════════════ AC-6 ═══════════════════════════════

    @Test
    @DisplayName("AC-6: PUT 全置換で version/contentRevision が+1、明示 null で消去でき、再読込でも同値")
    void AC6_PUT全置換で版が進み明示nullで消去できる() throws Exception {
        Long owner = user("本人");
        JsonNode created = create(owner, EXERCISE, DAY, values("duration", "30", "intensity", "5", "menu", "ラン"), "メモ");
        String id = created.path("id").asText();

        JsonNode updated = expect(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(write(updateBody(0, DAY.plusDays(1), values("duration", "40", "intensity", null, "menu", null), null))),
                owner), 200).path("data");
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(updated.path("contentRevision").asLong()).isEqualTo(2);
        assertThat(updated.path("recordedOn").asText()).isEqualTo("2026-11-02");
        assertThat(valueOf(updated, "duration").asText()).isEqualTo("40");
        assertThat(valueOf(updated, "intensity").isNull()).as("任意数値の明示 null は 0 にしない").isTrue();
        assertThat(valueOf(updated, "menu").isNull()).isTrue();
        assertThat(updated.path("privateNote").isNull()).isTrue();
        assertThat(updated.path("recordTimezone").asText()).isEqualTo(TOKYO);
        assertThat(updated.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());

        assertThat(expect(http(get(ME + "/{id}", id), owner), 200).path("data")).isEqualTo(updated);
    }

    @Test
    @DisplayName("AC-6: version 不一致・同 version 再送は409 COMMON_003")
    void AC6_version不一致と同version再送は409() throws Exception {
        Long owner = user("本人");
        String id = create(owner, WEIGHT, DAY, values("weight", "60.50"), null).path("id").asText();

        expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(write(updateBody(5, DAY, values("weight", "61.00"), null))), owner), 409, "COMMON_003");

        String body = write(updateBody(0, DAY, values("weight", "61.00"), null));
        expect(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body), owner), 200);
        // 応答消失後の同 version 再送は重複検知で409（API 契約 §4.1）。
        expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body), owner), 409, "COMMON_003");

        JsonNode after = expect(http(get(ME + "/{id}", id), owner), 200).path("data");
        assertThat(after.path("version").asLong()).isEqualTo(1);
        assertThat(after.path("contentRevision").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-6: definition / recordTimezone の変更は400で原本は不変")
    void AC6_定義とTZの変更は400() throws Exception {
        Long owner = user("本人");
        String id = create(owner, WEIGHT, DAY, values("weight", "60.50"), null).path("id").asText();

        Map<String, Object> tzChange = updateBody(0, DAY, values("weight", "61.00"), null);
        tzChange.put("recordTimezone", "UTC");
        expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(write(tzChange)), owner),
                400, "COMMON_001");

        Map<String, Object> definitionChange = updateBody(0, DAY, values("weight", "61.00"), null);
        definitionChange.put("definitionUuid", STUDY.toString());
        expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(write(definitionChange)), owner),
                400, "COMMON_001");

        JsonNode after = expect(http(get(ME + "/{id}", id), owner), 200).path("data");
        assertThat(after.path("version").asLong()).isZero();
        assertThat(after.path("recordTimezone").asText()).isEqualTo(TOKYO);
        assertThat(after.path("definition").path("definitionUuid").asText()).isEqualTo(WEIGHT.toString());
        assertThat(valueOf(after, "weight").asText()).isEqualTo("60.50");
    }

    @Test
    @DisplayName("AC-6: 必須 field を values から省略した PUT は400、削除済みへの PUT は404")
    void AC6_必須省略は400で削除済みは404() throws Exception {
        Long owner = user("本人");
        String id = create(owner, EXERCISE, DAY, values("duration", "30", "intensity", "5", "menu", "ラン"), null).path("id").asText();

        expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(write(updateBody(0, DAY, values("intensity", "5", "menu", "ラン"), null))), owner), 400, "COMMON_001");

        expect(http(delete(ME + "/{id}", id).param("version", "0").header("Idempotency-Key", UUID.randomUUID().toString()), owner), 200);
        expectError(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(write(updateBody(1, DAY, values("duration", "30", "intensity", "5", "menu", "ラン"), null))), owner),
                404, "EFFORT_001");
    }

    // ═══════════════════════════════ AC-7 ═══════════════════════════════

    @Test
    @DisplayName("AC-7: 私的 note のみの編集でも contentRevision が+1 し、値は不変")
    void AC7_noteのみ編集でもcontentRevisionが進む() throws Exception {
        Long owner = user("本人");
        JsonNode created = create(owner, STUDY, DAY, values("subject", "英語", "duration", "25"), "旧メモ");
        String id = created.path("id").asText();

        JsonNode updated = expect(http(put(ME + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(write(updateBody(0, DAY, values("subject", "英語", "duration", "25"), "新メモ"))), owner), 200).path("data");
        assertThat(updated.path("contentRevision").asLong()).isEqualTo(2);
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(updated.path("privateNote").asText()).isEqualTo("新メモ");
        assertThat(updated.path("values")).isEqualTo(created.path("values"));
        assertThat(updated.path("share").isNull()).as("note は共有状態を作らない").isTrue();
    }

    // ═══════════════════════════════ AC-8 ═══════════════════════════════

    @Test
    @DisplayName("AC-8: DELETE ?version は200 state=DELETED、詳細404・一覧と集計から除外、物理行は論理削除で残る")
    void AC8_削除は200で一覧集計から除外() throws Exception {
        Long owner = user("本人");
        String kept = create(owner, STUDY, DAY, values("subject", "数学", "duration", "10"), null).path("id").asText();
        String removed = create(owner, STUDY, DAY, values("subject", "数学", "duration", "50"), null).path("id").asText();

        JsonNode deleted = expect(http(delete(ME + "/{id}", removed).param("version", "0")
                .header("Idempotency-Key", UUID.randomUUID().toString()), owner), 200).path("data");
        assertThat(fieldNames(deleted)).isEqualTo(Set.of("id", "version", "state"));
        assertThat(deleted.path("id").asText()).isEqualTo(removed);
        assertThat(deleted.path("state").asText()).isEqualTo("DELETED");
        assertThat(deleted.path("version").asLong()).isGreaterThanOrEqualTo(1);

        expectError(http(get(ME + "/{id}", removed), owner), 404, "EFFORT_001");
        List<String> ids = new ArrayList<>();
        expect(http(get(ME), owner), 200).path("data").forEach(r -> ids.add(r.path("id").asText()));
        assertThat(ids).containsExactly(kept);

        JsonNode stats = expect(http(get(ME + "/stats").param("date_from", "2026-11-01").param("date_to", "2026-11-01")
                .param("definition_uuid", STUDY.toString()).param("field_key", "duration").param("chart", "BAR"), owner), 200)
                .path("data");
        assertThat(stats.path("total").asText()).as("削除済みは集計から除外").isEqualTo("10.00");

        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE id = UNHEX(REPLACE(?1,'-','')) AND deleted_at IS NOT NULL",
                removed)).as("論理削除で物理行は残る").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-8: DELETE の version 不一致は409・version 欠落は400")
    void AC8_削除のversion不一致409と欠落400() throws Exception {
        Long owner = user("本人");
        String id = create(owner, WEIGHT, DAY, values("weight", "60.00"), null).path("id").asText();
        expectError(http(delete(ME + "/{id}", id).param("version", "3")
                .header("Idempotency-Key", UUID.randomUUID().toString()), owner), 409, "COMMON_003");
        expectError(http(delete(ME + "/{id}", id).header("Idempotency-Key", UUID.randomUUID().toString()), owner),
                400, "COMMON_001");
        assertThat(expect(http(get(ME + "/{id}", id), owner), 200).path("data").path("version").asLong()).isZero();
    }

    // ─────────────────────────────────────────────────────────────

    private Map<String, Object> updateBody(long version, LocalDate recordedOn, Map<String, String> values, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("recordedOn", recordedOn.toString());
        body.put("values", valueList(values));
        body.put("privateNote", note);
        return body;
    }

    private static JsonNode field(JsonNode definition, String key) {
        for (JsonNode field : definition.path("fields")) {
            if (key.equals(field.path("fieldKey").asText())) {
                return field;
            }
        }
        throw new AssertionError("field " + key + " が無い: " + definition);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
