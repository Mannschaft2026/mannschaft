package com.mannschaft.app.effortrecord.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** F06.4.1 共有 retry の入力（API 契約 §4）。同一 desired command を再実行するだけで内容は変えない。 */
public record EffortShareRetryRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @PositiveOrZero Long shareVersion) {
}
