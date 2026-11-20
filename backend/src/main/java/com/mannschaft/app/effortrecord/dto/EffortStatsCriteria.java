package com.mannschaft.app.effortrecord.dto;

import com.mannschaft.app.effortrecord.enums.EffortChartType;
import com.mannschaft.app.effortrecord.enums.EffortGrain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F06.4.1 集計 API の条件。grain は省略可（省略時は親設計の期間規則）。
 * DAY は 62 日以下、MONTH は 366 日以下。chart と semantic が不適合なら 400。
 */
public record EffortStatsCriteria(
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID definitionUuid,
        String fieldKey,
        EffortChartType chart,
        EffortGrain grain) {
}
