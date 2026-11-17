package com.mannschaft.app.effortrecord.dto;

import com.mannschaft.app.effortrecord.enums.EffortAggregation;
import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;

import java.util.List;

/**
 * F06.4.1 項目定義（API 契約 §2 FieldDefinition）。labelKey は標準プリセットのみ非 null。
 * min / max は固定小数の 10 進文字列または null。
 */
public record EffortFieldDefinition(
        String fieldKey,
        String label,
        String labelKey,
        EffortFieldType type,
        boolean required,
        EffortSemantic semantic,
        String unit,
        EffortAggregation aggregation,
        int scale,
        String min,
        String max,
        List<Option> options) {

    /** SELECT の選択肢。 */
    public record Option(String key, String label, String labelKey) {
    }
}
