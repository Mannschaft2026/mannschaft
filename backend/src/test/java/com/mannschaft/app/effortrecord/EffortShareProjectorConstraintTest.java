package com.mannschaft.app.effortrecord;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.dto.EffortShareMapping;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.service.EffortShareProjector;
import com.mannschaft.app.effortrecord.service.EffortSnapshotCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F06.4.1 PR-3b 検分指摘（CMP-261115-2145）: 共有 projection の組み立てが共有先定義の必須・型・定義制約を守ること
 * （API 契約 §4「target 必須の不足は valueOverride で入力」「target 必須値は未選択でも入力検証する」、
 * valueOverride は原本と同じ型・定義制約で検証し、日時は UTC へ正規化する）。
 *
 * <p>projector は純粋な部品なので Spring を起動せずに検査する。fieldErrors に値を載せないことも併せて見る（AC-61）。</p>
 */
@DisplayName("F06.4.1 PR-3b 共有 projection の必須・型・定義制約")
class EffortShareProjectorConstraintTest {

    private final EffortShareProjector projector = new EffortShareProjector(new EffortSnapshotCodec());

    private static EffortFieldDefinition field(String key, EffortFieldType type, boolean required, int scale, String min,
                                               String max) {
        return new EffortFieldDefinition(key, key, null, type, required, null, null, null, scale, min, max, List.of());
    }

    private static EffortDefinitionSnapshot snapshot(EffortFieldDefinition... fields) {
        return new EffortDefinitionSnapshot(UUID.fromString("01938a00-0000-7000-8000-0000000000aa"), 1, EffortSourceType.TEAM,
                null, 1L, "共有先", null, null, Arrays.asList(fields));
    }

    /** 原本: メモ（TEXT・任意）だけ。値は空。 */
    private static final EffortDefinitionSnapshot SOURCE = snapshot(field("memo", EffortFieldType.TEXT, false, 0, null, null));

    private static Map<String, String> sourceValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("memo", null);
        return values;
    }

    private static EffortShareMapping override(String target, String value) {
        return new EffortShareMapping(target, null, value);
    }

    private EffortShareProjector.Built build(EffortDefinitionSnapshot target, List<EffortShareMapping> mappings,
                                             String... selected) {
        return projector.build(SOURCE, sourceValues(), target, mappings, List.of(selected), "Asia/Tokyo");
    }

    private static void assertInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
            assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::getField).contains(field);
        });
    }

    // ───────────────────────────── 必須（指摘4） ─────────────────────────────

    @Test
    @DisplayName("共有先の必須項目の mapping を省略すると（選択していても・allowlist から外しても）400 COMMON_001")
    void 必須項目のmapping省略は400() {
        EffortDefinitionSnapshot target = snapshot(field("must", EffortFieldType.TEXT, true, 0, null, null),
                field("opt", EffortFieldType.NUMBER, false, 0, null, null));
        assertInvalid(() -> build(target, List.of(override("opt", "3")), "must", "opt"), "mappings.must");
        assertInvalid(() -> build(target, List.of(override("opt", "3")), "opt"), "mappings.must");
        assertInvalid(() -> build(target, List.of(override("must", null), override("opt", "3")), "opt"), "mappings.must");
    }

    @Test
    @DisplayName("必須項目が原本の空値に対応づくだけ（mapping 後が null）でも 400。valueOverride で入れれば未選択のまま通り、projection に入れない")
    void 必須はmapping後の値で検査し非選択値はprojectionに入れない() {
        EffortDefinitionSnapshot target = snapshot(field("must", EffortFieldType.TEXT, true, 0, null, null),
                field("opt", EffortFieldType.NUMBER, false, 0, null, null));
        assertInvalid(() -> build(target, List.of(new EffortShareMapping("must", "memo", null), override("opt", "3")), "opt"),
                "mappings.must");

        EffortShareProjector.Built built = build(target, List.of(override("must", "入力した"), override("opt", "3")), "opt");
        assertThat(built.projection().fields()).extracting(EffortSharedRecord.Field::fieldKey).containsExactly("opt");
    }

    // ───────────────────────────── 型・定義制約（指摘5） ─────────────────────────────

    @Test
    @DisplayName("valueOverride の DATE は暦日として検証し（2026-02-30 は 400）、DATETIME はオフセット付き ISO8601 を UTC へ正規化する")
    void 日付と日時のoverrideを検証しUTCへ正規化() {
        EffortDefinitionSnapshot target = snapshot(field("day", EffortFieldType.DATE, false, 0, null, null),
                field("at", EffortFieldType.DATETIME, false, 0, null, null));
        assertInvalid(() -> build(target, List.of(override("day", "2026-02-30")), "day"), "mappings[0].valueOverride");
        assertInvalid(() -> build(target, List.of(override("day", "明日")), "day"), "mappings[0].valueOverride");
        assertInvalid(() -> build(target, List.of(override("at", "2026-11-05 10:00")), "at"), "mappings[0].valueOverride");

        EffortShareProjector.Built built = build(target,
                List.of(override("day", "2026-11-05"), override("at", "2026-11-05T10:00:00+09:00")), "day", "at");
        assertThat(built.projection().fields()).extracting(EffortSharedRecord.Field::value)
                .containsExactly("2026-11-05", "2026-11-05T01:00:00Z");
    }

    @Test
    @DisplayName("valueOverride の NUMBER は共有先定義の scale・min・max で検証する（範囲外・桁超過・下限なしの負数は 400）")
    void 数値のoverrideはscaleとminmaxで検証() {
        EffortDefinitionSnapshot target = snapshot(field("count", EffortFieldType.NUMBER, false, 0, "0", "100"),
                field("km", EffortFieldType.NUMBER, false, 1, null, null));
        assertInvalid(() -> build(target, List.of(override("count", "1.5")), "count"), "mappings[0].valueOverride");
        assertInvalid(() -> build(target, List.of(override("count", "101")), "count"), "mappings[0].valueOverride");
        assertInvalid(() -> build(target, List.of(override("count", "-1")), "count"), "mappings[0].valueOverride");
        assertInvalid(() -> build(target, List.of(override("km", "1.25")), "km"), "mappings[0].valueOverride");
        assertInvalid(() -> build(target, List.of(override("km", "-0.5")), "km"), "mappings[0].valueOverride");

        EffortShareProjector.Built built = build(target, List.of(override("count", "100"), override("km", "12.5")),
                "count", "km");
        assertThat(built.projection().fields()).extracting(EffortSharedRecord.Field::value).containsExactly("100", "12.5");
    }

    @Test
    @DisplayName("検証エラーの fieldErrors に入力値を載せない（AC-61）")
    void fieldErrorsに値を載せない() {
        EffortDefinitionSnapshot target = snapshot(field("count", EffortFieldType.NUMBER, false, 0, "0", "100"));
        assertThatThrownBy(() -> build(target, List.of(override("count", "987654")), "count"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getFieldErrors().toString())
                        .doesNotContain("987654"));
    }
}
