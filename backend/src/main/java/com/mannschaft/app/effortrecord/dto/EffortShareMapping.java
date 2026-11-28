package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * F06.4.1 共有 mapping の1行（API 契約 §4）。共有先 TEAM 定義の target 項目へ、原本の source 項目の値を引き継ぐか、
 * 共有設定だけに保存する値（valueOverride）を入れる。両方の同時指定・note の指定は 400（Service で検証）。
 *
 * @param targetFieldKey 共有先定義の fieldKey
 * @param sourceFieldKey 原本定義の fieldKey（null 可）
 * @param valueOverride  共有設定だけに保存する値（null 可）
 */
public record EffortShareMapping(
        @NotBlank @Size(max = 64) String targetFieldKey,
        @Size(max = 64) String sourceFieldKey,
        @Size(max = 2000) String valueOverride) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
