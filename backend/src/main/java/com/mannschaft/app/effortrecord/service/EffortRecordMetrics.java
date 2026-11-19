package com.mannschaft.app.effortrecord.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * F06.4.1 の観測（親設計 §5）。メトリクス名は {@code effort.*}。
 *
 * <p>tag は操作名（create/update/delete/list/get/stats）と結果（success/client_error/error）の
 * 固定語彙だけ。<b>userId・shareId・業務値を tag にしない</b>（AC-61）。成功は commit 後に数える。</p>
 */
@Component
public class EffortRecordMetrics {

    private final MeterRegistry registry;

    public EffortRecordMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 操作を実行し、成否を数える。業務例外（4xx）と想定外の失敗を区別する。 */
    public <T> T measure(String operation, Supplier<T> body) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = body.get();
            afterCommit(() -> count(operation, "success"));
            return result;
        } catch (com.mannschaft.app.common.BusinessException e) {
            count(operation, "client_error");
            throw e;
        } catch (RuntimeException e) {
            count(operation, "error");
            throw e;
        } finally {
            sample.stop(registry.timer("effort.operation.duration", "operation", operation));
        }
    }

    private void count(String operation, String result) {
        registry.counter("effort.operations", "operation", operation, "result", result).increment();
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
