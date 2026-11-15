package com.mannschaft.app.effortrecord.dto;

import jakarta.validation.constraints.NotNull;

/** F06.4.1 チーム定義 snapshot 取得リクエスト（POST /effort-records/me/definition-snapshots）。 */
public record CreateEffortDefinitionSnapshotRequest(
        @NotNull Long teamId,
        @NotNull Long teamTemplateId) {
}
