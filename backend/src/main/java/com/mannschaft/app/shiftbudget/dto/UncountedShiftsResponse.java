package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * CMP-260909-2152 締め後に未計上の勤務の一覧応答。
 *
 * @param revision 追加（ADD）を送るときの expected_revision（締め後訂正の現在の通し番号。訂正が無ければ 0）
 */
public record UncountedShiftsResponse(
        @JsonProperty("items") List<UncountedShiftResponse> items,
        @JsonProperty("revision") long revision
) {
}
