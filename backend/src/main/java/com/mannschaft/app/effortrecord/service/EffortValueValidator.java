package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.dto.EffortFieldValue;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F06.4.1 原本の入力検証と正規化（親設計 §3、API 契約 §2・§3）。
 *
 * <p>検証失敗は {@code COMMON_001}（400）と {@code fieldErrors}（field は {@code values.<fieldKey>}・
 * {@code privateNote}・{@code recordedOn} 等）で返す。<b>メッセージに入力値を載せない</b>
 * （体重・科目・メモは機微情報のため、応答・ログのどちらにも出さない。AC-61）。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortValueValidator {

    /** 1 記録の最大 field 数（親設計 §5）。 */
    public static final int MAX_FIELDS = 15;
    /** 値 JSON の最大バイト数（親設計 §5: 本文 JSON 合計 64KiB）。 */
    public static final int MAX_VALUES_BYTES = 64 * 1024;
    /** 私的メモの最大文字数。 */
    public static final int MAX_NOTE_LENGTH = 2000;
    /** 記録日の下限。 */
    public static final LocalDate MIN_RECORDED_ON = LocalDate.of(1900, 1, 1);

    private static final int MAX_SUBJECT_LENGTH = 100;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_NUMBER_LENGTH = 24;
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    private final EffortSnapshotCodec codec;

    /** 検証・正規化済みの入力。values は snapshot の field 順で全 field の key を持つ（未入力は null）。 */
    public record Validated(Map<String, String> values, String privateNote) {
    }

    /**
     * 値を検証する。
     *
     * @param requireAllKeys true なら snapshot の全 field が values に含まれていることを要求する（PUT 全置換）。
     *                       false なら任意 field の省略を null として扱う（作成）。必須 field の省略は常に拒否。
     */
    public Validated validate(EffortDefinitionSnapshot snapshot, List<EffortFieldValue> input,
                              String privateNote, boolean requireAllKeys) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        Map<String, EffortFieldDefinition> definitions = new LinkedHashMap<>();
        snapshot.fields().forEach(field -> definitions.put(field.fieldKey(), field));

        if (input.size() > MAX_FIELDS) {
            errors.add(error("values", "項目数が上限を超えています"));
        }
        Map<String, String> given = new HashMap<>();
        for (EffortFieldValue entry : input) {
            if (!definitions.containsKey(entry.fieldKey())) {
                errors.add(error("values." + entry.fieldKey(), "定義にない項目です"));
            } else if (given.containsKey(entry.fieldKey())) {
                errors.add(error("values." + entry.fieldKey(), "項目が重複しています"));
            } else {
                given.put(entry.fieldKey(), entry.value());
            }
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (EffortFieldDefinition field : snapshot.fields()) {
            boolean present = given.containsKey(field.fieldKey());
            if (!present && (requireAllKeys || field.required())) {
                errors.add(error("values." + field.fieldKey(), "項目を指定してください"));
                continue;
            }
            normalized.put(field.fieldKey(), normalizeField(field, present ? given.get(field.fieldKey()) : null,
                    "values." + field.fieldKey(), errors));
        }

        String note = privateNote == null || privateNote.isBlank() ? null : privateNote;
        if (note != null && note.codePointCount(0, note.length()) > MAX_NOTE_LENGTH) {
            errors.add(error("privateNote", "私的メモは" + MAX_NOTE_LENGTH + "文字以内で入力してください"));
        }
        if (errors.isEmpty()
                && codec.valuesJson(normalized).getBytes(StandardCharsets.UTF_8).length > MAX_VALUES_BYTES) {
            errors.add(error("values", "入力内容が大きすぎます"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, errors);
        }
        return new Validated(normalized, note);
    }

    /** recordTimezone が IANA の地域 ID か。 */
    public static boolean isIanaZone(String zone) {
        return zone != null && zone.length() <= 64 && ZONES.contains(zone);
    }

    /**
     * 1 項目の値を定義（型・必須・scale・min/max・選択肢・文字数）で検証し、保存形へ正規化する（日時は UTC の Instant 表記）。
     * 原本の値と、共有設定だけに保存する valueOverride（API 契約 §4）の両方がこの規則を使う。
     *
     * @param path   違反時の fieldErrors[].field
     * @param errors 違反を追記する一覧（メッセージに入力値を載せない）
     * @return 正規化した値。未入力・違反は null
     */
    public static String normalizeField(EffortFieldDefinition field, String raw, String path,
                                        List<ErrorResponse.FieldError> errors) {
        if (raw == null || raw.isBlank()) {
            if (field.required()) {
                errors.add(error(path, "必須項目です"));
            }
            return null;
        }
        return switch (field.type()) {
            case NUMBER -> number(field, raw, path, errors);
            case TEXT -> text(field, raw, path, errors);
            case SELECT -> {
                if (field.options() == null || field.options().stream().noneMatch(o -> o.key().equals(raw))) {
                    errors.add(error(path, "選択肢にない値です"));
                    yield null;
                }
                yield raw;
            }
            case DATE -> {
                try {
                    yield LocalDate.parse(raw).toString();
                } catch (DateTimeException e) {
                    errors.add(error(path, "日付は YYYY-MM-DD 形式で入力してください"));
                    yield null;
                }
            }
            case DATETIME -> {
                try {
                    yield OffsetDateTime.parse(raw).toInstant().toString();
                } catch (DateTimeException e) {
                    errors.add(error(path, "日時はオフセット付きの ISO8601 形式で入力してください"));
                    yield null;
                }
            }
            case CHECKBOX -> {
                if (!"true".equals(raw) && !"false".equals(raw)) {
                    errors.add(error(path, "true または false で入力してください"));
                    yield null;
                }
                yield raw;
            }
        };
    }

    private static String number(EffortFieldDefinition field, String raw, String path, List<ErrorResponse.FieldError> errors) {
        if (raw.length() > MAX_NUMBER_LENGTH || !DECIMAL.matcher(raw).matches()) {
            errors.add(error(path, "数値は小数の10進表記で入力してください"));
            return null;
        }
        BigDecimal value = new BigDecimal(raw);
        if (value.signum() < 0 && (field.min() == null || value.compareTo(new BigDecimal(field.min())) < 0)) {
            // 負数は構文としては正しい 10 進数。下限を下回る（下限なしなら負数すべて）ので「形式不正」ではなく範囲外として伝える。
            errors.add(error(path, "入力できる範囲を超えています"));
            return null;
        }
        if (value.scale() > field.scale()) {
            errors.add(error(path, field.scale() == 0 ? "整数で入力してください"
                    : "小数は" + field.scale() + "桁以内で入力してください"));
            return null;
        }
        if (field.min() != null && value.compareTo(new BigDecimal(field.min())) < 0
                || field.max() != null && value.compareTo(new BigDecimal(field.max())) > 0) {
            errors.add(error(path, "入力できる範囲を超えています"));
            return null;
        }
        return raw;
    }

    private static String text(EffortFieldDefinition field, String raw, String path, List<ErrorResponse.FieldError> errors) {
        int limit = field.semantic() == EffortSemantic.SUBJECT ? MAX_SUBJECT_LENGTH : MAX_TEXT_LENGTH;
        if (raw.codePointCount(0, raw.length()) > limit) {
            errors.add(error(path, limit + "文字以内で入力してください"));
            return null;
        }
        return raw;
    }

    private static ErrorResponse.FieldError error(String field, String message) {
        return new ErrorResponse.FieldError(field, message);
    }
}
