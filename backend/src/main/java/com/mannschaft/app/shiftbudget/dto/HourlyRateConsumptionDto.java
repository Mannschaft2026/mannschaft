package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * 保存済み時給ごとの消化内訳。現在の時給から過去分を再計算しない。
 *
 * @param hourlyRateSnapshot 消化記録に保存された時給（0を含む。記録の由来は推測しない）
 * @param hours 同じ記録時給に属する保存済み勤務時間の和
 * @param amount 同じ記録時給に属する保存済み金額の和
 */
@Builder
public record HourlyRateConsumptionDto(
        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("hourly_rate_snapshot")
        BigDecimal hourlyRateSnapshot,

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("hours")
        BigDecimal hours,

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("amount")
        BigDecimal amount
) {
}
