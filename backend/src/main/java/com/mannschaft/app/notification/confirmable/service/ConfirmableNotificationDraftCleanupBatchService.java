package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * CMP-261126-1558: 確認通知の下書きの掃除バッチ。
 *
 * <p>updated_at から {@value #RETENTION_DAYS} 日を超えた下書きを日次で物理削除する。
 * 作りは {@link ConfirmableNotificationExpiryBatchService} に揃える（{@code @Scheduled}・ShedLock・
 * {@code @BatchEndpoint}・{@code @BackgroundFeaturePolicy}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationDraftCleanupBatchService {

    /** 下書きの保持日数（updated_at 起点）。 */
    public static final int RETENTION_DAYS = 90;

    private final ConfirmableNotificationDraftRepository draftRepository;

    /** 日次の入口。現在時刻から {@value #RETENTION_DAYS} 日より前に更新された下書きを消す。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。90日更新の無い未送信の下書きを物理削除する処理であり、停止しても下書きが残るだけで、再開後に同じ条件で拾い直せる")
    @BatchEndpoint(name = "notification-confirmable-draft-cleanup-daily",
            description = "更新から90日を超えた確認通知の下書きを毎日 03:30 に物理削除する")
    @Scheduled(cron = "0 30 3 * * *") // 毎日 AM 3:30
    @SchedulerLock(
            name = "confirmableNotificationDraftCleanupBatch",
            lockAtLeastFor = "PT5M",
            lockAtMostFor = "PT10M")
    @Transactional // 自己呼び出しの purgeExpired は AOP を経由しないため、入口にも付ける
    public void runBatch() {
        Instant now = Instant.now();
        log.info("確認通知の下書き掃除バッチ開始: {}", now);
        long deleted = purgeExpired(now);
        log.info("確認通知の下書き掃除バッチ完了: 削除={}", deleted);
    }

    /**
     * 指定時刻から {@value #RETENTION_DAYS} 日を超えた下書きを消す。
     *
     * @param now 基準時刻
     * @return 削除件数
     */
    @Transactional
    public long purgeExpired(Instant now) {
        return draftRepository.deleteByUpdatedAtBefore(now.minus(Duration.ofDays(RETENTION_DAYS)));
    }
}
