package com.mannschaft.app.search;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 入力検証・正規化を通った検索条件。
 *
 * <p>{@link SearchContributor} は種別ごとに別スレッドで呼ばれ、リクエストスレッドの ThreadLocal
 * （{@code TimezoneContextHolder}）を参照できない。閲覧者 TZ はリクエストスレッドで解決して
 * {@code viewerZone} に載せ、非同期境界を越えて渡す。</p>
 *
 * @param query      正規化済みキーワード（trim・連続／全角スペースを半角 1 つへ畳み込み済み）
 * @param terms      スペース区切りの語（AND 条件）。演算子・ワイルドカードは未エスケープの生文字
 * @param scopeType  スコープ絞り込み（{@code TEAM} / {@code ORGANIZATION}。未指定は null）
 * @param scopeId    スコープ ID（{@code scopeType} 指定時のみ非 null）
 * @param dateFrom   期間開始日（閲覧者 TZ の暦日。null 可。USER / TEAM / ORGANIZATION には適用しない）
 * @param dateTo     期間終了日（閲覧者 TZ の暦日。null 可。同上）
 * @param authorId   投稿者・作成者 ID（null 可。同上）
 * @param viewerZone 閲覧者 TZ（{@code dateFrom} / {@code dateTo} の日付境界を作るゾーン。
 *                   {@code UserZoneLocalDateTimeParser#requestZone()} で解決した値。null 不可）
 */
public record SearchCriteria(
        String query,
        List<String> terms,
        String scopeType,
        Long scopeId,
        LocalDate dateFrom,
        LocalDate dateTo,
        Long authorId,
        ZoneId viewerZone) {

    public SearchCriteria {
        Objects.requireNonNull(viewerZone, "viewerZone");
    }
}
