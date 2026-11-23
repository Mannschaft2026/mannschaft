package com.mannschaft.app.effortrecord.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** F06.4.1 共有 PUT の入力（API 契約 §4）。previewToken は preview が返した opaque 文字列。 */
public record EffortShareRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 16384) String previewToken) {
}
