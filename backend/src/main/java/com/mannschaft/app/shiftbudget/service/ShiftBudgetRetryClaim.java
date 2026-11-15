package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;

/**
 * CMP-260909-2152: 失敗イベント再試行の着手権（claim）。
 *
 * <p>{@link ShiftBudgetFailedEventService#claim} が状態条件付き UPDATE で RETRYING＋retry_count++ を
 * コミットした結果を値で返す。{@code attempt} はその時点の retry_count（試行番号）で、
 * {@link ShiftBudgetFailedEventService#recordResult} の条件（{@code status='RETRYING' AND retry_count=?}）に使う。
 * 段取り役は TX 外でこの値だけを持ち回る（JPA 管理エンティティを TX 外へ持ち出さない）。</p>
 *
 * @param failedEventId  失敗イベント ID
 * @param organizationId 組織 ID
 * @param eventType      イベント種別
 * @param sourceId       ソース ID（allocation_id / alert_id 等）
 * @param payload        再実行用ペイロード（JSON）
 * @param attempt        試行番号（claim 後の retry_count）
 */
public record ShiftBudgetRetryClaim(
        Long failedEventId,
        Long organizationId,
        ShiftBudgetFailedEventType eventType,
        Long sourceId,
        String payload,
        int attempt) {
}
