package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * F06.4.1 原本更新リクエスト（API 契約 §3 Update。全置換 PUT）。
 * definition / recordTimezone は変更不可。snapshot の全 field を values に渡し、任意未入力は明示 null。
 */
public record UpdateEffortRecordRequest(
        @NotNull @Min(0) Long version,
        @NotNull LocalDate recordedOn,
        @NotNull @Valid List<EffortFieldValue> values,
        String privateNote) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
