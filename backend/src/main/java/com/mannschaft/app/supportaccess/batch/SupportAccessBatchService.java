package com.mannschaft.app.supportaccess.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * F10.9 サポート閲覧の定期バッチ（失効の確定と保存期間の物理削除）。
 *
 * <p>期限の判定自体は {@code SupportAccessGrantValidator} が時刻で即時に行うため、失効バッチは
 * 状態表示（EXPIRED）と監査を確定させる後追いであり、止まっても代理閲覧が延命されることはない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportAccessBatchService {

    private final SupportAccessGrantService grantService;
    private final SupportAccessRetentionService retentionService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "期限切れの依頼・許可を EXPIRED に確定し監査を残す。判定自体は毎リクエスト時刻で行うため停止しても延命されないが、"
                    + "状態表示と監査の確定が遅れるため常時実行する")
    @BatchEndpoint(name = "supportaccess-expire-overdue", description = "期限切れのサポート閲覧の依頼（72時間）・許可（24時間）を5分ごとに EXPIRED にする")
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "supportAccessExpireOverdue", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
    public void expireOverdue() {
        int count = grantService.expireOverdue();
        if (count > 0) {
            log.info("[SupportAccessBatch] 期限切れを EXPIRED に更新: {}件", count);
        }
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "保存期間（閲覧記録1年・依頼と許可3年）を過ぎた行を物理削除する。停止すると個人に紐づく閲覧記録が保存期間を超えて残る")
    @BatchEndpoint(name = "supportaccess-retention-daily", description = "保存期間を過ぎたサポート閲覧の記録を毎日 03:40 に物理削除する")
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "supportAccessRetentionDaily", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purgeExpiredRecords() {
        retentionService.purgeExpiredRecords();
    }
}
