package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 原本作成リクエスト（API 契約 §3 Create）。
 * definitionToken は PRESET のみ null 可。TEAM 定義では snapshot API の 10 分有効署名 token が必須。
 * privateNote のみ null 可（他は必須）。owner や snapshot 本体はクライアントから受け取らない。
 */
public record CreateEffortRecordRequest(
        @NotNull LocalDate recordedOn,
        @NotNull String recordTimezone,
        @NotNull UUID definitionUuid,
        @NotNull Integer definitionRevision,
        String definitionToken,
        @NotNull List<@NotNull @Valid EffortFieldValue> values,
        String privateNote) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
