package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * F06.4.1 チーム共有の観測（親設計 §5）。メトリクス名は {@code effort.share.*}。
 *
 * <p>tag は操作名・結果・deny 理由の固定語彙だけ。<b>userId・shareId・業務値を tag にしない</b>（AC-61）。
 * 成功は commit 後に数える。</p>
 */
@Component
public class EffortShareMetrics {

    private final MeterRegistry registry;

    public EffortShareMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 共有操作（preview/share/retry/revoke/moderate/list/detail/definitions）を実行し、成否を数える。 */
    public <T> T measure(String operation, Supplier<T> body) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = body.get();
            afterCommit(() -> count(operation, "success"));
            return result;
        } catch (BusinessException e) {
            count(operation, "client_error");
            throw e;
        } catch (RuntimeException e) {
            count(operation, "error");
            throw e;
        } finally {
            sample.stop(registry.timer("effort.share.operation.duration", "operation", operation));
        }
    }

    /** 同期パイプラインの結果（success/failed/stale/moderated/discarded）。 */
    public void sync(String result) {
        registry.counter("effort.share.sync", "result", result).increment();
    }

    /** 共有要求から VISIBLE 確定までの滞留時間。 */
    public void pending(Duration elapsed) {
        registry.timer("effort.share.pending.duration").record(elapsed);
    }

    /** F00 の deny 理由別件数（reason は固定語彙）。 */
    public void denied(String reason) {
        registry.counter("effort.share.f00.denied", "reason", reason).increment();
    }

    private void count(String operation, String result) {
        registry.counter("effort.share.operations", "operation", operation, "result", result).increment();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
