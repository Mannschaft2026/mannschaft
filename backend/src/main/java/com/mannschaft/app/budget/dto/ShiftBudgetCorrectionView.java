package com.mannschaft.app.budget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CMP-260909-2152 訂正行の応答（履歴・登録結果で共通）。金額は明細1行ぶんの前後と、月合計の増減。
 */
public record ShiftBudgetCorrectionView(
        @JsonProperty("id") UUID id,
        @JsonProperty("revision") long revision,
        @JsonProperty("kind") String kind,
        @JsonProperty("line_kind") String lineKind,
        @JsonProperty("consumption_id") Long consumptionId,
        @JsonProperty("added_line_id") UUID addedLineId,
        @JsonProperty("reverses_correction_id") UUID reversesCorrectionId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("work_date") LocalDate workDate,
        @JsonProperty("slot_id") Long slotId,
        @JsonProperty("shift_id") Long shiftId,
        @JsonProperty("before_hours") BigDecimal beforeHours,
        @JsonProperty("before_hourly_rate") BigDecimal beforeHourlyRate,
        @JsonProperty("before_amount") BigDecimal beforeAmount,
        @JsonProperty("before_line_status") String beforeLineStatus,
        @JsonProperty("after_hours") BigDecimal afterHours,
        @JsonProperty("after_hourly_rate") BigDecimal afterHourlyRate,
        @JsonProperty("after_amount") BigDecimal afterAmount,
        @JsonProperty("after_line_status") String afterLineStatus,
        @JsonProperty("exact_delta") BigDecimal exactDelta,
        @JsonProperty("posted_delta") BigDecimal postedDelta,
        @JsonProperty("content") String content,
        @JsonProperty("reason") String reason,
        @JsonProperty("actor_user_id") Long actorUserId,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("budget_transaction_id") Long budgetTransactionId
) {

    public static ShiftBudgetCorrectionView from(BudgetMonthlyCloseCorrectionEntity e) {
        return new ShiftBudgetCorrectionView(e.getId(), e.getRevision(), e.getKind(), e.getLineKind(),
                e.getConsumptionId(), e.getAddedLineId(), e.getReversesCorrectionId(), e.getUserId(), e.getWorkDate(),
                e.getSlotId(), e.getShiftId(), e.getBeforeHours(), e.getBeforeHourlyRate(), e.getBeforeAmount(),
                e.getBeforeLineStatus(), e.getAfterHours(), e.getAfterHourlyRate(), e.getAfterAmount(),
                e.getAfterLineStatus(), e.getExactDelta(), e.getPostedDelta(), e.getContent(), e.getReason(),
                e.getActorUserId(), e.getCreatedAt(), e.getBudgetTransactionId());
    }
}
