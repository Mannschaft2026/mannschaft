package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CMP-260909-2152 月次締め後訂正の取消（REVERSAL）リクエスト（試練段階の骨格）。
 */
public record MonthlyCloseReversalRequest(
        @JsonProperty("command_id") String commandId,
        @JsonProperty("expected_revision") Long expectedRevision,
        @JsonProperty("reason") String reason
) {
}
