package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

/** F06.4.1 チーム定義 snapshot 取得リクエスト（POST /effort-records/me/definition-snapshots）。 */
public record CreateEffortDefinitionSnapshotRequest(
        @NotNull Long teamId,
        @NotNull Long teamTemplateId) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }
}
