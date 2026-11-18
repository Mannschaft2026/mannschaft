package com.mannschaft.app.effortrecord.dto;

import com.mannschaft.app.effortrecord.enums.EffortChartType;
import com.mannschaft.app.effortrecord.enums.EffortGrain;
import com.mannschaft.app.effortrecord.enums.EffortStatsEmptyReason;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 集計レスポンス（API 契約 §5 Stats）。filter 値はそのまま echo する。
 * value / total は固定小数の 10 進文字列（例 "30.00"）または null。
 */
public record EffortStatsResponse(
        UUID definitionUuid,
        String fieldKey,
        String unit,
        String semantic,
        EffortChartType chart,
        EffortGrain grain,
        LocalDate dateFrom,
        LocalDate dateTo,
        long totalSampleCount,
        List<Bucket> buckets,
        List<Category> categories,
        String total,
        EffortStatsEmptyReason emptyReason) {

    /** BAR/LINE の期間バケット（空白バケット含め昇順）。 */
    public record Bucket(LocalDate startOn, LocalDate endOn, String value, long sampleCount) {
    }

    /** PIE のカテゴリ（値降順 / key 昇順）。 */
    public record Category(String key, String label, String value, long sampleCount) {
    }
}
