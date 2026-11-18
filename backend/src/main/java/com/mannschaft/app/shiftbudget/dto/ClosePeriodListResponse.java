package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodResolver;

import java.time.LocalDate;
import java.util.List;

/**
 * 締め期間一覧の応答（CMP-260909-2152）。FE 試練と共有する契約: {@code data.items:[{period_key, period_start, period_end,
 * closing_day}]}。
 */
public record ClosePeriodListResponse(@JsonProperty("items") List<ClosePeriodItem> items) {

    /** 締め期間の1件。 */
    public record ClosePeriodItem(
            @JsonProperty("period_key") String periodKey,
            @JsonProperty("period_start") LocalDate periodStart,
            @JsonProperty("period_end") LocalDate periodEnd,
            @JsonProperty("closing_day") int closingDay
    ) {
    }

    public static ClosePeriodListResponse from(List<ShiftBudgetClosePeriodResolver.ResolvedPeriod> periods) {
        return new ClosePeriodListResponse(periods.stream()
                .map(p -> new ClosePeriodItem(p.periodKey().toString(), p.start(), p.end(), p.closingDay()))
                .toList());
    }
}
