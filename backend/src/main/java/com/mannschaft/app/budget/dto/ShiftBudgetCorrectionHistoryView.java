package com.mannschaft.app.budget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * CMP-260909-2152 締め事実と訂正履歴（revision 昇順、1ページ最大100件）。
 */
public record ShiftBudgetCorrectionHistoryView(
        @JsonProperty("close") ShiftBudgetCloseView close,
        @JsonProperty("items") List<ShiftBudgetCorrectionView> items,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total") long total
) {
}
