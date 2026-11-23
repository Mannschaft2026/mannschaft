package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * F06.4.1 チーム履歴の定義候補（API 契約 §4 {@code GET /teams/{teamId}/effort-records/definitions}）。
 * 当該チームで VISIBLE な履歴が 1 件以上ある定義だけを返す。
 */
public record EffortTeamDefinitionCandidate(
        UUID definitionUuid,
        String templateName,
        @JsonProperty("isRetired") boolean isRetired) {
}
