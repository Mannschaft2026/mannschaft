package com.mannschaft.app.activity;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * 活動統計の集計単位（F06.4 §15.2）。週の起点は月曜（ISO 8601）。
 */
public enum ActivityStatsGranularity {
    WEEK, MONTH, QUARTER, YEAR;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    /**
     * クエリパラメータから解釈する。省略時は {@link #MONTH}。不正値は 400 {@code COMMON_001}。
     */
    public static ActivityStatsGranularity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONTH;
        }
        for (ActivityStatsGranularity value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        throw new BusinessException(CommonErrorCode.COMMON_001);
    }

    /** 日付が属するバケットの開始日。 */
    public LocalDate bucketStart(LocalDate date) {
        return switch (this) {
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case QUARTER -> LocalDate.of(date.getYear(), ((date.getMonthValue() - 1) / 3) * 3 + 1, 1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    /** 次のバケットの開始日。 */
    public LocalDate next(LocalDate bucketStart) {
        return switch (this) {
            case WEEK -> bucketStart.plusWeeks(1);
            case MONTH -> bucketStart.plusMonths(1);
            case QUARTER -> bucketStart.plusMonths(3);
            case YEAR -> bucketStart.plusYears(1);
        };
    }

    /** バケットの表示ラベル（WEEK=月曜の YYYY-MM-DD、MONTH=YYYY-MM、QUARTER=YYYY-Qn、YEAR=YYYY）。 */
    public String label(LocalDate bucketStart) {
        return switch (this) {
            case WEEK -> bucketStart.format(DAY);
            case MONTH -> bucketStart.format(YEAR_MONTH);
            case QUARTER -> bucketStart.getYear() + "-Q" + ((bucketStart.getMonthValue() - 1) / 3 + 1);
            case YEAR -> String.valueOf(bucketStart.getYear());
        };
    }
}
