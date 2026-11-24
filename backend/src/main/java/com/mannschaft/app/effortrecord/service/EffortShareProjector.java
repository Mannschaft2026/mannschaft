package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.dto.EffortShareMapping;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * F06.4.1 共有 projection の組み立て（API 契約 §4・データ設計 §2・§7）。
 *
 * <p>原本の定義 snapshot と値、共有先 TEAM 定義（共有用に凍結する snapshot）、mapping、allowlist から、
 * チームへ見せる項目だけの projection を決定的に作る。私的 note は mapping 元にできず、未選択の項目は projection に入れない。
 * 原本訂正では凍結した共有定義・mapping・allowlist を使って新しい値で作り直す（live 定義を再要求しない）。</p>
 *
 * <p>preview・共有 PUT の入力（{@link #build}）は、valueOverride を原本と同じ型・定義制約（{@link EffortValueValidator#normalizeField}）で
 * 検証・正規化し（日時は UTC）、共有先の必須項目は mapping を適用した後の値で、allowlist から外した項目も含めて検査する
 * （API 契約 §4「target 必須値は未選択でも入力検証するが、非選択値を projection へ含めない」）。</p>
 *
 * <p>ログには何も書かない（値・note・定義を記録しない。AC-61）。検証エラーの fieldErrors にも値を載せない。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortShareProjector {

    private static final TypeReference<List<EffortShareMapping>> MAPPINGS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final EffortSnapshotCodec codec;

    /** canonical 直列化用（宣言順・空白なし）。 */
    private final ObjectMapper canonical = JsonMapper.builder().build();

    /**
     * 共有内容（チームへ見せる部分）。recordTimezone・templateName・定義 revision と allowlist 項目だけ。
     *
     * @param recordTimezone     原本の記録 TZ
     * @param templateName       共有先テンプレート名
     * @param definitionRevision 共有先定義の revision
     * @param fields             allowlist 項目（共有先定義の順）
     */
    public record Projection(String recordTimezone, String templateName, int definitionRevision,
                             List<EffortSharedRecord.Field> fields) {
    }

    /** 正規化した共有 command（mapping・allowlist を共有先定義の順に並べたもの）。 */
    public record Command(List<EffortShareMapping> mappings, List<String> selectedFieldKeys) {
    }

    /** preview で確定した command と projection。 */
    public record Built(Command command, Projection projection, String projectionJson, String projectionHash) {
    }

    /**
     * preview の入力を検証し、正規化した command と projection を作る。
     *
     * @throws BusinessException 400 COMMON_001（allowlist 空・未知/重複 key・source と override の同時指定・
     *                           note や未知の source・型/意味/単位の不一致・値の書式/定義制約の違反・共有先の必須項目の不足）
     */
    public Built build(EffortDefinitionSnapshot source, Map<String, String> sourceValues, EffortDefinitionSnapshot target,
                       List<EffortShareMapping> mappings, List<String> selectedFieldKeys, String recordTimezone) {
        Map<String, EffortFieldDefinition> targetByKey = new LinkedHashMap<>();
        target.fields().forEach(f -> targetByKey.put(f.fieldKey(), f));
        Map<String, EffortFieldDefinition> sourceByKey = new LinkedHashMap<>();
        source.fields().forEach(f -> sourceByKey.put(f.fieldKey(), f));

        if (selectedFieldKeys == null || selectedFieldKeys.isEmpty()) {
            throw invalid("selectedFieldKeys", "共有する項目を 1 つ以上選んでください");
        }
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < selectedFieldKeys.size(); i++) {
            String key = selectedFieldKeys.get(i);
            if (!targetByKey.containsKey(key) || !selected.add(key)) {
                throw invalid("selectedFieldKeys[" + i + "]", "共有先の定義にない項目か、重複した項目です");
            }
        }
        Map<String, EffortShareMapping> byTarget = new LinkedHashMap<>();
        List<EffortShareMapping> input = mappings == null ? List.of() : mappings;
        for (int i = 0; i < input.size(); i++) {
            EffortShareMapping m = input.get(i);
            String path = "mappings[" + i + "]";
            if (m == null || !targetByKey.containsKey(m.targetFieldKey()) || byTarget.containsKey(m.targetFieldKey())) {
                throw invalid(path + ".targetFieldKey", "共有先の定義にない項目か、重複した項目です");
            }
            if (m.sourceFieldKey() != null && m.valueOverride() != null) {
                throw invalid(path, "元の項目と入力値は同時に指定できません");
            }
            if (m.sourceFieldKey() != null) {
                EffortFieldDefinition from = sourceByKey.get(m.sourceFieldKey());
                if (from == null) {
                    throw invalid(path + ".sourceFieldKey", "元の記録にない項目は共有に使えません");
                }
                if (!compatible(from, targetByKey.get(m.targetFieldKey()))) {
                    throw invalid(path + ".sourceFieldKey", "型・意味・単位が同じ項目だけ引き継げます");
                }
            }
            String override = m.valueOverride() == null ? null
                    : normalizeOverride(targetByKey.get(m.targetFieldKey()), m.valueOverride(), path + ".valueOverride");
            byTarget.put(m.targetFieldKey(), new EffortShareMapping(m.targetFieldKey(), m.sourceFieldKey(), override));
        }
        // 共有先定義の順に正規化（未指定の target は source なし・override なし）
        List<EffortShareMapping> normalized = new ArrayList<>();
        List<String> orderedSelected = new ArrayList<>();
        for (EffortFieldDefinition field : target.fields()) {
            EffortShareMapping m = byTarget.get(field.fieldKey());
            normalized.add(m == null ? new EffortShareMapping(field.fieldKey(), null, null) : m);
            if (selected.contains(field.fieldKey())) {
                orderedSelected.add(field.fieldKey());
            }
        }
        Command command = new Command(List.copyOf(normalized), List.copyOf(orderedSelected));
        requireTargetRequired(sourceByKey, sourceValues, target, command);
        return project(source, sourceValues, target, command, recordTimezone);
    }

    /**
     * 正規化済み command から projection を作る（原本訂正・retry では凍結した共有定義と command をそのまま使う）。
     * SELECT の値は共有先の選択肢へ写し、対応する選択肢が無い値は推測せず null にする。
     */
    public Built project(EffortDefinitionSnapshot source, Map<String, String> sourceValues, EffortDefinitionSnapshot target,
                         Command command, String recordTimezone) {
        Map<String, EffortShareMapping> byTarget = new LinkedHashMap<>();
        command.mappings().forEach(m -> byTarget.put(m.targetFieldKey(), m));
        Map<String, EffortFieldDefinition> sourceByKey = new LinkedHashMap<>();
        source.fields().forEach(f -> sourceByKey.put(f.fieldKey(), f));
        Set<String> selected = new HashSet<>(command.selectedFieldKeys());
        List<EffortSharedRecord.Field> fields = new ArrayList<>();
        for (EffortFieldDefinition field : target.fields()) {
            if (!selected.contains(field.fieldKey())) {
                continue;
            }
            String value = resolve(byTarget.get(field.fieldKey()), sourceByKey, sourceValues, field);
            fields.add(new EffortSharedRecord.Field(field.fieldKey(), field.label(), field.type(),
                    field.semantic() == null ? null : field.semantic().name(), field.unit(), value, display(field, value)));
        }
        Projection projection = new Projection(recordTimezone, target.templateName(), target.revision(), List.copyOf(fields));
        String json = write(projection);
        return new Built(command, projection, json, codec.hash(json));
    }

    // ───────────────────────────── 直列化 ─────────────────────────────

    public String commandMappingsJson(Command command) {
        return write(command.mappings());
    }

    public String selectedJson(Command command) {
        return write(command.selectedFieldKeys());
    }

    public Command readCommand(String mappingsJson, String selectedJson) {
        try {
            return new Command(canonical.readValue(mappingsJson, MAPPINGS), canonical.readValue(selectedJson, STRINGS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの共有 command を読み込めません", e);
        }
    }

    public Projection readProjection(String json) {
        try {
            return canonical.readValue(json, Projection.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの共有 projection を読み込めません", e);
        }
    }

    /** command の canonical hash（previewToken へ束縛する）。 */
    public String commandHash(Command command) {
        return codec.hash(write(command));
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /** mapping を適用した後の値（override を優先し、なければ source の値を共有先へ写す）。 */
    private static String resolve(EffortShareMapping m, Map<String, EffortFieldDefinition> sourceByKey,
                                  Map<String, String> sourceValues, EffortFieldDefinition target) {
        if (m == null) {
            return null;
        }
        if (m.valueOverride() != null) {
            return m.valueOverride();
        }
        if (m.sourceFieldKey() != null) {
            return mapValue(sourceByKey.get(m.sourceFieldKey()), sourceValues.get(m.sourceFieldKey()), target);
        }
        return null;
    }

    /**
     * 共有先の必須項目が mapping 適用後に値を持つか（allowlist の選択に関わらず検査する。API 契約 §4）。
     * 不足は fieldErrors=mappings.&lt;targetFieldKey&gt;（値は載せない）。
     */
    private static void requireTargetRequired(Map<String, EffortFieldDefinition> sourceByKey, Map<String, String> sourceValues,
                                              EffortDefinitionSnapshot target, Command command) {
        Map<String, EffortShareMapping> byTarget = new LinkedHashMap<>();
        command.mappings().forEach(m -> byTarget.put(m.targetFieldKey(), m));
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        for (EffortFieldDefinition field : target.fields()) {
            if (field.required() && resolve(byTarget.get(field.fieldKey()), sourceByKey, sourceValues, field) == null) {
                errors.add(new ErrorResponse.FieldError("mappings." + field.fieldKey(),
                        "共有先の必須項目です。元の項目を対応づけるか、値を入力してください"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, errors);
        }
    }

    /** valueOverride を原本と同じ型・定義制約で検証し、保存形へ正規化する（空は null＝未入力）。 */
    private static String normalizeOverride(EffortFieldDefinition field, String value, String path) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        String normalized = EffortValueValidator.normalizeField(optional(field), value, path, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, errors);
        }
        return normalized;
    }

    /** 必須の不足は mapping 適用後にまとめて検査するため、値の検証では必須を問わない。 */
    private static EffortFieldDefinition optional(EffortFieldDefinition f) {
        return new EffortFieldDefinition(f.fieldKey(), f.label(), f.labelKey(), f.type(), false, f.semantic(), f.unit(),
                f.aggregation(), f.scale(), f.min(), f.max(), f.options());
    }

    private static boolean compatible(EffortFieldDefinition from, EffortFieldDefinition to) {
        return from.type() == to.type() && from.semantic() == to.semantic() && Objects.equals(from.unit(), to.unit());
    }

    /** SELECT は option key（同じ key、なければ同じ表示名の option）へ写す。その他は保存値のまま。 */
    private static String mapValue(EffortFieldDefinition from, String raw, EffortFieldDefinition to) {
        if (raw == null || from == null) {
            return null;
        }
        if (to.type() != EffortFieldType.SELECT) {
            return raw;
        }
        for (EffortFieldDefinition.Option option : to.options()) {
            if (option.key().equals(raw)) {
                return raw;
            }
        }
        String label = null;
        for (EffortFieldDefinition.Option option : from.options()) {
            if (option.key().equals(raw)) {
                label = option.label();
            }
        }
        for (EffortFieldDefinition.Option option : to.options()) {
            if (option.label().equals(label)) {
                return option.key();
            }
        }
        // 共有先に対応する選択肢がない値は送らない（推測で別の選択肢へ写さない）。
        return null;
    }

    private static String display(EffortFieldDefinition field, String value) {
        if (value == null || field.type() != EffortFieldType.SELECT) {
            return value;
        }
        for (EffortFieldDefinition.Option option : field.options()) {
            if (option.key().equals(value)) {
                return option.label();
            }
        }
        return null;
    }

    private String write(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("共有内容を直列化できません", e);
        }
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
    }
}
