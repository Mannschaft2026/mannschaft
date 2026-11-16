package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CMP-260909-2152 月次締め後訂正の登録リクエスト（試練段階の骨格）。
 *
 * <p>kind は MODIFY / CANCEL / ADD。MODIFY・CANCEL は line_kind（ORIGINAL / ADDED）と
 * consumption_id または added_line_id で明細を特定する。ADD は user_id・work_date・hours・
 * hourly_rate（必須・サーバ再計算なし）と任意の slot_id / shift_id を受け取る。
 * 検証規則（必須・範囲）は出陣で付与する。</p>
 */
public record MonthlyCloseCorrectionRequest(
        @JsonProperty("command_id") String commandId,
        @JsonProperty("expected_revision") Long expectedRevision,
        @JsonProperty("kind") String kind,
        @JsonProperty("line_kind") String lineKind,
        @JsonProperty("consumption_id") Long consumptionId,
        @JsonProperty("added_line_id") String addedLineId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("work_date") LocalDate workDate,
        @JsonProperty("slot_id") Long slotId,
        @JsonProperty("shift_id") Long shiftId,
        @JsonProperty("hours") BigDecimal hours,
        @JsonProperty("hourly_rate") BigDecimal hourlyRate,
        @JsonProperty("content") String content,
        @JsonProperty("reason") String reason
) {
}
