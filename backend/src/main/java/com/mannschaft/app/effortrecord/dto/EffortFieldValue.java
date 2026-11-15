package com.mannschaft.app.effortrecord.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * F06.4.1 項目値（API 契約 §2 FieldValue）。value は固定小数の 10 進文字列 / option key / 文字列 / null。
 */
public record EffortFieldValue(
        @NotNull @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String fieldKey,
        String value) {
}
