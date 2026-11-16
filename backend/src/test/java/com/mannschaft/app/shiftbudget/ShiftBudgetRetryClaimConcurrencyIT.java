package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetRetryBatchJob;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRetryClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

/**
 * CMP-260909-2152 試練 32・37 — 再試行の claim（着手権）と条件付き結果記録を実 DB で検査する。
 *
 * <p>確定設計 r3(2)・r4-3:</p>
 * <ul>
 *   <li>claim は shiftbudget TX の<b>状態条件付き UPDATE</b>で RETRYING＋retry_count++ とし、
 *       retry_count を試行番号として返す。RETRYING でリース（10 分）が切れていなければ 409。</li>
 *   <li>recordResult は {@code WHERE id=? AND status='RETRYING' AND retry_count=?} の条件付き UPDATE。
 *       0 行なら結果を捨てる（旧実行が新実行の結果や手動解決を上書きしない）。</li>
 *   <li>手動 API とバッチは同じ段取り役を通り、同時に claim しても一方は 409 かスキップ。</li>
 * </ul>
 */
@DisplayName("CMP-260909-2152 試練32・37 再試行の claim と条件付き結果記録（実DB・並行）")
class ShiftBudgetRetryClaimConcurrencyIT extends ShiftBudgetCmp2152RealDbTestBase {

    @Autowired private ShiftBudgetFailedEventService failedEventService;
    @Autowired private ShiftBudgetRetryBatchJob retryBatchJob;

    // ═══════════════════════════ 試練32 ═══════════════════════════

    @Test
    @DisplayName("試練32 同じ失敗イベントを 2 スレッドが同時に claim すると、一方だけが着手し他方は 409")
    void 同時claimは一方だけが着手する() throws Exception {
        Long eventId = seedNotificationEvent(seedAllocation("10000", "8000", "0"));

        CyclicBarrier start = new CyclicBarrier(2);
        List<ShiftBudgetRetryClaim> claims = new CopyOnWriteArrayList<>();
        List<BusinessException> conflicts = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        Runnable task = () -> {
            try {
                start.await(30, TimeUnit.SECONDS);
                claims.add(failedEventService.claim(eventId));
            } catch (BusinessException e) {
                conflicts.add(e);
            } catch (Throwable t) {
                unexpected.add(t);
            }
        };
        Thread a = new Thread(task, "cmp2152-claim-A");
        Thread b = new Thread(task, "cmp2152-claim-B");
        a.start();
        b.start();
        a.join(60_000);
        b.join(60_000);

        assertThat(unexpected).as("claim は業務例外（409）以外で落ちてはならない").isEmpty();
        assertThat(claims).as("着手できるのは 1 スレッドだけ").hasSize(1);
        assertThat(conflicts).as("もう一方は実行中リース内として 409 で拒否される").hasSize(1);
        assertThat(claims.get(0).attempt()).as("初回着手の試行番号は 1").isEqualTo(1);
        assertThat(failedEventStatus(eventId)).isEqualTo("RETRYING");
        assertThat(failedEventRetryCount(eventId)).as("二重着手なら 2 になる").isEqualTo(1);
    }

    @Test
    @DisplayName("試練32 リースが切れた RETRYING は再 claim でき、試行番号が進む")
    void リース切れは再claimできる() {
        Long eventId = seedNotificationEvent(seedAllocation("10000", "8000", "0"));

        ShiftBudgetRetryClaim first = failedEventService.claim(eventId);
        expireLease(eventId);
        ShiftBudgetRetryClaim second = failedEventService.claim(eventId);

        assertThat(first.attempt()).isEqualTo(1);
        assertThat(second.attempt()).as("実行者が落ちたまま放置された行を救えないと永久に RETRYING で固まる").isEqualTo(2);
        assertThat(failedEventRetryCount(eventId)).isEqualTo(2);
        assertThat(failedEventStatus(eventId)).isEqualTo("RETRYING");
    }

    @Test
    @DisplayName("試練32 リース内の RETRYING へ手動再試行すると 409 で、実行もされず回数も増えない")
    void リース内の手動再試行は409() throws Exception {
        Long allocationId = seedAllocation("10000", "8000", "0");
        Long eventId = seedNotificationEvent(allocationId);
        failedEventService.claim(eventId); // バッチ側が着手中の状態を作る
        AtomicInteger resendCalls = countResendFor(allocationId);

        int status = manualRetry(eventId);

        assertThat(status).as("実行中の行を手動で二重実行させない").isEqualTo(409);
        assertThat(resendCalls.get()).as("409 なら再送そのものが走ってはならない").isZero();
        assertThat(failedEventRetryCount(eventId)).isEqualTo(1);
        assertThat(failedEventStatus(eventId)).isEqualTo("RETRYING");
    }

    @Test
    @DisplayName("試練32 手動再試行の実行中にバッチが走っても、バッチは待たずにスキップし再送は 1 回だけ")
    void 手動実行中のバッチはスキップする() throws Exception {
        Long allocationId = seedAllocation("10000", "8000", "0");
        Long eventId = seedNotificationEvent(allocationId);

        CountDownLatch manualInside = new CountDownLatch(1);
        CountDownLatch releaseManual = new CountDownLatch(1);
        AtomicInteger resendCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (resendCalls.getAndIncrement() == 0) {
                manualInside.countDown();
                releaseManual.await(30, TimeUnit.SECONDS);
            }
            return null;
        }).when(notificationResendService).resend(anyList(), anyString(), nullable(String.class), nullable(String.class),
                any(), nullable(String.class), nullable(String.class), nullable(String.class),
                eq(allocationId), nullable(Long.class), nullable(String.class));

        AtomicReference<Integer> manualStatus = new AtomicReference<>();
        Thread manual = new Thread(() -> {
            try {
                manualStatus.set(manualRetry(eventId));
            } catch (Exception e) {
                manualStatus.set(-1);
            }
        }, "cmp2152-manual");
        manual.start();
        assertThat(manualInside.await(30, TimeUnit.SECONDS)).as("手動再試行が実行段に入れないと競合を作れない").isTrue();

        CountDownLatch batchDone = new CountDownLatch(1);
        Thread batch = new Thread(() -> {
            try {
                retryBatchJob.run();
            } finally {
                batchDone.countDown();
            }
        }, "cmp2152-batch");
        batch.start();
        boolean batchFinishedWhileManualRunning = batchDone.await(10, TimeUnit.SECONDS);

        releaseManual.countDown();
        manual.join(60_000);
        batch.join(60_000);

        assertThat(batchFinishedWhileManualRunning)
                .as("claim を TX 外で確定させていないと、バッチは手動側の行ロックで待たされる（実行中に TX を張りっぱなし）")
                .isTrue();
        assertThat(resendCalls.get()).as("同じ失敗イベントの再送が 2 回走ってはならない").isEqualTo(1);
        assertThat(manualStatus.get()).isEqualTo(200);
        assertThat(failedEventRetryCount(eventId)).isEqualTo(1);
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
    }

    // ═══════════════════════════ 試練37 ═══════════════════════════

    @Test
    @DisplayName("試練37 旧実行 A の失敗結果は、後から着手した実行 B の SUCCEEDED を上書きしない（0 行で破棄）")
    void 旧実行の結果は新実行を上書きしない() {
        Long eventId = seedNotificationEvent(seedAllocation("10000", "8000", "0"));

        ShiftBudgetRetryClaim a = failedEventService.claim(eventId);
        expireLease(eventId);
        ShiftBudgetRetryClaim b = failedEventService.claim(eventId);

        boolean appliedB = failedEventService.recordResult(eventId, b.attempt(), true, null);
        boolean appliedA = failedEventService.recordResult(eventId, a.attempt(), false, "CMP2152 旧実行の失敗");

        assertThat(appliedB).isTrue();
        assertThat(appliedA).as("試行番号が古い結果は条件付き UPDATE で 0 行になり捨てられる").isFalse();
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
        assertThat(failedEventRetryCount(eventId)).isEqualTo(2);
        assertThat((String) failedEventRow(eventId).get("error_message"))
                .as("旧実行のエラーで成功記録が汚れてはならない")
                .isNotEqualTo("CMP2152 旧実行の失敗");
    }

    @Test
    @DisplayName("試練37 実行中に手動解決されたら、実行側の結果（失敗・成功とも）は捨てられ MANUAL_RESOLVED が残る")
    void 実行中の手動解決は保持される() throws Exception {
        Long eventId = seedNotificationEvent(seedAllocation("10000", "8000", "0"));

        ShiftBudgetRetryClaim running = failedEventService.claim(eventId);
        int resolveStatus = manualResolve(eventId);

        boolean appliedFailure = failedEventService.recordResult(eventId, running.attempt(), false, "CMP2152 実行側の失敗");
        boolean appliedSuccess = failedEventService.recordResult(eventId, running.attempt(), true, null);

        assertThat(resolveStatus).isEqualTo(200);
        assertThat(appliedFailure).as("手動解決後に実行側の失敗で PENDING へ戻すとバッチが再び拾ってしまう").isFalse();
        assertThat(appliedSuccess).isFalse();
        assertThat(failedEventStatus(eventId)).isEqualTo("MANUAL_RESOLVED");
    }

    // ───────────── ヘルパー ─────────────

    /** 外部境界（通知配送）だけを止めた NOTIFICATION_SEND 失敗イベント。再送の入口は既存の再送サービス。 */
    private Long seedNotificationEvent(Long allocationId) {
        String payload = "{\"user_ids\":[" + budgetAdminId + "],\"type\":\"SHIFT_BUDGET_THRESHOLD_ALERT\","
                + "\"threshold_percent\":80,\"source_type\":\"SHIFT_BUDGET_ALLOCATION\","
                + "\"source_id\":" + allocationId + ",\"scope_id\":" + orgId + "}";
        return seedFailedEvent(ShiftBudgetFailedEventType.NOTIFICATION_SEND, allocationId, payload);
    }

    private AtomicInteger countResendFor(Long allocationId) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            calls.incrementAndGet();
            return null;
        }).when(notificationResendService).resend(anyList(), anyString(), nullable(String.class), nullable(String.class),
                any(), nullable(String.class), nullable(String.class), nullable(String.class),
                eq(allocationId), nullable(Long.class), nullable(String.class));
        return calls;
    }
}
