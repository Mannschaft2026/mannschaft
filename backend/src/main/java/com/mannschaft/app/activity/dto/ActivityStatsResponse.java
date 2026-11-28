package com.mannschaft.app.activity.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 活動統計レスポンスDTO（F06.4 §15.2。CMP-261124-0159 で契約を一本化）。
 *
 * <p>閲覧者によらず全員共通の数字（{@code topParticipantsVisible} / {@code topParticipants} を除く）。
 * {@code averageParticipants} は対象 0 件で null、{@code topParticipants} は見せない閲覧者に null（空配列と区別する）。</p>
 */
@Getter
@RequiredArgsConstructor
@Builder
public class ActivityStatsResponse {

    private final String scopeType;
    private final Long scopeId;
    private final LocalDate dateFrom;
    private final LocalDate dateTo;
    private final String granularity;
    private final long totalActivities;
    private final long totalParticipations;
    private final long uniqueParticipants;
    private final BigDecimal averageParticipants;
    private final List<TemplateCount> byTemplate;
    private final List<PeriodCount> byPeriod;
    private final boolean topParticipantsVisible;
    private final List<TopParticipant> topParticipants;

    /** テンプレート別件数。テンプレート無しの記録は {@code templateId} / {@code templateName} とも null。 */
    @Getter
    @RequiredArgsConstructor
    public static class TemplateCount {
        private final Long templateId;
        private final String templateName;
        private final long count;
    }

    /** 集計単位ごとの件数と延べ参加数（期間内の全バケットを 0 埋め）。 */
    @Getter
    @RequiredArgsConstructor
    public static class PeriodCount {
        private final LocalDate periodStart;
        private final String periodLabel;
        private final long count;
        private final long participations;
    }

    /** 上位参加者。退会受付済み（匿名化済み）の利用者は {@code userId} / {@code displayName} とも null。 */
    @Getter
    @RequiredArgsConstructor
    public static class TopParticipant {
        private final Long userId;
        private final String displayName;
        private final long participationCount;
    }
}
