package com.mannschaft.app.effortrecord.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * F06.4.1 共有 PUT の入力（API 契約 §4）。previewToken は preview が返した opaque 文字列。
 *
 * <p>mappings・selectedFieldKeys は preview で送ったものをそのまま送り直す（上限は preview と同じ 15 項目）。token は
 * command を値ごと詰めず hash で束縛するため（最大入力でも token の長さは一定）、server は送り直された command を
 * preview と同じ規則で正規化し、token の command hash・projection hash と一致しなければ 409 COMMON_003（再 preview）にする。</p>
 */
public record EffortShareRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 16384) String previewToken,
        @NotNull @Size(max = 15) List<@Valid EffortShareMapping> mappings,
        @NotNull @Size(max = 15) List<@NotBlank @Size(max = 64) String> selectedFieldKeys) {
}
