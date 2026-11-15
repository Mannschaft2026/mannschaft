package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * F06.4.1 冪等 receipt の期限削除バッチ（データ設計 §2「保持24時間で期限削除バッチ」・AC-62）。
 *
 * <p>{@code expires_at} が現在時刻（{@code utcClock} Bean）より前の {@code effort_operation_receipts} を物理削除する。
 * 期限内の receipt は残す。receipt には私的メモを含む応答 JSON が入るため、個人情報の長期保管にならないよう
 * 毎時回収する。1 回の DELETE を {@value #CHUNK} 行に区切り、長いロックを作らない。</p>
 */
@Slf4j
@Service
public class EffortOperationReceiptCleanupBatchService {

    private static final int CHUNK = 1000;

    private final TransactionTemplate transactions;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public EffortOperationReceiptCleanupBatchService(PlatformTransactionManager transactionManager,
                                                     @Qualifier("utcClock") Clock clock) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 毎時 17 分に期限切れ receipt を回収する。 */
    @Scheduled(cron = "${mannschaft.effort-record.receipt-cleanup-cron:0 17 * * * *}")
    @SchedulerLock(name = "effortOperationReceiptCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    @BatchEndpoint(name = "effort-receipt-cleanup-hourly", description = "期限切れの頑張り記録 Idempotency receipt を削除する")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "停止すると私的メモを含む冪等 receipt の応答 JSON が保持期限を超えて残り続けるため常時実行する")
    public void scheduledPurge() {
        int deleted = purgeExpired();
        if (deleted > 0) {
            log.info("頑張り記録の期限切れ receipt を削除: count={}", deleted);
        }
    }

    /** 期限切れ receipt を削除し、削除件数を返す。 */
    public int purgeExpired() {
        Instant threshold = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int total = 0;
        int deleted;
        do {
            Integer count = transactions.execute(status -> entityManager
                    .createNativeQuery("DELETE FROM effort_operation_receipts WHERE expires_at < ?1 LIMIT " + CHUNK)
                    .setParameter(1, threshold)
                    .executeUpdate());
            deleted = count == null ? 0 : count;
            total += deleted;
        } while (deleted == CHUNK);
        return total;
    }
}
