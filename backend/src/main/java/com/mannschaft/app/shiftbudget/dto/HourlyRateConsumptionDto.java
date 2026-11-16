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
 * @param addedCount 同じ時給の、締め後に追加した有効な明細の件数（hours / amount に含まれる。CMP-260909-2152）
 * @param addedAmount 同、金額
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
        BigDecimal amount,

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("added_count")
        int addedCount,

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("added_amount")
        BigDecimal addedAmount
) {
    public HourlyRateConsumptionDto {
        addedAmount = addedAmount == null ? BigDecimal.ZERO : addedAmount;
    }
}
