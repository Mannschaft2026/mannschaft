package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CMP-260909-2152 締め後に未計上の勤務（ワンクリック追加の候補）。
 *
 * <p>slot・人・勤務日・時間・その勤務日に有効な時給を、確認ダイアログの初期値として返す。
 * 時給が未設定の勤務は hourly_rate=null・add_available=false で、unavailable_reason に理由コードを入れる
 * （HOURLY_RATE_MISSING / PLANNED_EXISTS）。</p>
 */
public record UncountedShiftResponse(
        @JsonProperty("slot_id") Long slotId,
        @JsonProperty("shift_id") Long shiftId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("work_date") LocalDate workDate,
        @JsonProperty("hours") BigDecimal hours,
        @JsonProperty("hourly_rate") BigDecimal hourlyRate,
        @JsonProperty("add_available") boolean addAvailable,
        @JsonProperty("unavailable_reason") String unavailableReason
) {
}
