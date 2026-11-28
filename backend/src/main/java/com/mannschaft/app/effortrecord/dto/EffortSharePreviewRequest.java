package com.mannschaft.app.effortrecord.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * F06.4.1 共有 preview の入力（API 契約 §4）。mappings・selectedFieldKeys は 1 記録の上限 15 field に合わせて上限を置く。
 * 空の allowlist は Service が 400 にする（fieldErrors=selectedFieldKeys）。
 */
public record EffortSharePreviewRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Positive Long teamId,
        @NotBlank @Size(max = 2048) String definitionToken,
        @NotNull @Size(max = 15) List<@Valid EffortShareMapping> mappings,
        @NotNull @Size(max = 15) List<@NotBlank @Size(max = 64) String> selectedFieldKeys) {
}
