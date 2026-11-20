package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * F06.4.1 項目値（API 契約 §2 FieldValue）。value は固定小数の 10 進文字列 / option key / 文字列 / null。
 */
public record EffortFieldValue(
        @NotNull @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String fieldKey,
        String value) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
