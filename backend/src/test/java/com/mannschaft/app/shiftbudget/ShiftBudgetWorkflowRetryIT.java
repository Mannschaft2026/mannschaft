package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.shiftbudget.batch.ShiftBudgetRetryBatchJob;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 試練 22・30・31・38 — 閾値警告とワークフロー起動の再試行を実 DB で検査する。
 *
 * <p>確定設計:</p>
 * <ul>
 *   <li>r2「閾値 ThresholdAlertCoordinator」: 訂正後消化額で判定し、alert INSERT は shiftbudget TX、
 *       ワークフロー起動は TX 外、alert への書き戻しは別 TX の条件付き UPDATE。</li>
 *   <li>r2 reader: 「ワークフロー再試行は閾値を再判定せず保存済み alert_id に対して起動」。
 *       訂正なし割当の消化額は max(消化額, 確定額)。</li>
 *   <li>r3(3)(4): workflow 側 {@code startOrResumeForSource} が同 source の申請を 1 件に保ち、
 *       作成と提出を同 TX で行い、既存 DRAFT は提出を再開する。</li>
 *   <li>r4-4: AC9a 新規作成＋提出失敗→申請・ステップ 0 件→再試行で 1 件提出・書き戻し一致。
 *       AC9b 既存 DRAFT→再試行で同じ申請 ID を提出し新申請なし。</li>
 * </ul>
 *
 * <p>ワークフロー起動の失敗は、雛形の承認者 JSON を壊す（提出時の承認者解析で必ず落ちる）という
 * <b>データ由来の実失敗</b>で作る。モックで例外を差し込まないため、実装がどの内部メソッドを
 * 経由しても同じ条件で落ちる。</p>
 */
@DisplayName("CMP-260909-2152 試練22・30・31・38 閾値警告とワークフロー起動の再試行（実DB）")
class ShiftBudgetWorkflowRetryIT extends ShiftBudgetCmp2152RealDbTestBase {

    @Autowired private ShiftBudgetRetryBatchJob retryBatchJob;
    @Autowired private WorkflowRequestRepository workflowRequestRepository;

    // ═══════════════════════════ 試練38（AC9a / AC9b） ═══════════════════════════

    @Test
    @DisplayName("試練38 AC9a 作成は通るが提出で落ちる→申請もステップも 0 件、着手/結果記録は残る→原因除去後の再試行で 1 件提出し alert へ書き戻す")
    void AC9a_提出失敗は申請を残さず再試行で1件提出する() throws Exception {
        Long templateId = seedWorkflowTemplate(true);
        configureOverLimitWorkflow(templateId);
        Long allocationId = seedAllocation("10000", "10000", "0");
        seedAlert(allocationId, 80, "10000");
        Long alertId = seedAlert(allocationId, 100, "10000");
        Long eventId = seedWorkflowStartEvent(allocationId, alertId, templateId);

        int first = manualRetry(eventId);

        assertThat(first).isEqualTo(200);
        assertThat(workflowRequestCountForTemplate(templateId))
                .as("作成と提出は同じ TX。提出で落ちたら下書きの申請を残してはならない")
                .isZero();
        assertThat(workflowStepCountForTemplate(templateId)).isZero();
        assertThat(alertWorkflowRequestId(alertId)).isNull();
        assertThat(failedEventStatus(eventId))
                .as("実行中の失敗でも結果記録（PENDING へ戻す）がコミットされ、次回の再試行対象に残る")
                .isEqualTo("PENDING");
        assertThat(failedEventRetryCount(eventId)).as("着手記録（retry_count++）が巻き戻っていない").isEqualTo(1);
        assertThat(failedEventRow(eventId).get("last_retried_at")).isNotNull();
        assertThat((String) failedEventRow(eventId).get("error_message"))
                .as("今回の失敗理由が記録される")
                .isNotEqualTo("CMP-260909-2152 試練: 初回失敗");

        repairWorkflowTemplate(templateId);
        int second = manualRetry(eventId);

        List<Long> requests = liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alertId);
        assertThat(second).isEqualTo(200);
        assertThat(requests).as("保存済み alert に対して申請がちょうど 1 件").hasSize(1);
        assertThat(workflowRequestStatus(requests.get(0))).isEqualTo("IN_PROGRESS");
        assertThat(workflowRequestStepCount(requests.get(0))).isEqualTo(1);
        assertThat(alertWorkflowRequestId(alertId)).as("alert の書き戻し先が提出した申請と一致").isEqualTo(requests.get(0));
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
        assertThat(failedEventRetryCount(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("試練38 AC9b 同じ alert の DRAFT 申請が残っていれば、再試行はその申請 ID を提出し新しい申請を作らない")
    void AC9b_既存DRAFTを再開する() throws Exception {
        Long templateId = seedWorkflowTemplate(false);
        configureOverLimitWorkflow(templateId);
        Long allocationId = seedAllocation("10000", "10000", "0");
        seedAlert(allocationId, 80, "10000");
        Long alertId = seedAlert(allocationId, 100, "10000");
        Long draftId = workflowRequestRepository.save(WorkflowRequestEntity.builder()
                .templateId(templateId)
                .scopeType("ORGANIZATION")
                .scopeId(orgId)
                .title("CMP2152 作成済み・未提出")
                .sourceType(ALERT_SOURCE_TYPE)
                .sourceId(alertId)
                .build()).getId();
        Long eventId = seedWorkflowStartEvent(allocationId, alertId, templateId);

        int status = manualRetry(eventId);

        assertThat(status).isEqualTo(200);
        assertThat(liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alertId))
                .as("新しい申請を作らず既存の下書き 1 件のまま")
                .containsExactly(draftId);
        assertThat(workflowRequestStatus(draftId)).as("下書きの提出が再開されている").isEqualTo("IN_PROGRESS");
        assertThat(workflowRequestStepCount(draftId)).isEqualTo(1);
        assertThat(alertWorkflowRequestId(alertId)).isEqualTo(draftId);
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
    }

    // ═══════════════════════════ 試練22 ═══════════════════════════

    @Test
    @DisplayName("試練22 alert 保存済み→起動失敗（バッチ）→減額で 100% 未満へ→再試行（バッチ）でも保存済み alert に 1 件起票し書き戻す")
    void 減額後のバッチ再試行でも保存済みalertに起票する() throws Exception {
        Long templateId = seedWorkflowTemplate(true);
        configureOverLimitWorkflow(templateId);
        Long allocationId = seedAllocation("10000", "10000", "0");
        seedAlert(allocationId, 80, "10000");
        Long alertId = seedAlert(allocationId, 100, "10000");
        Long eventId = seedWorkflowStartEvent(allocationId, alertId, templateId);

        retryBatchJob.run();

        assertThat(failedEventStatus(eventId))
                .as("バッチでも実行中の失敗は結果記録としてコミットされ PENDING で残る")
                .isEqualTo("PENDING");
        assertThat(failedEventRetryCount(eventId)).isEqualTo(1);
        assertThat(liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alertId)).isEmpty();

        // 締め後の減額訂正で消化が 100% を下回った状態（閾値を再判定すれば起票されない）
        jdbc.update("UPDATE shift_budget_allocations SET consumed_amount = 3000 WHERE id = ?", allocationId);
        repairWorkflowTemplate(templateId);
        expireLease(eventId);

        retryBatchJob.run();

        List<Long> requests = liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alertId);
        assertThat(requests)
                .as("再試行は閾値を再判定せず、保存済み alert_id に対して起動する（減額で起票が消えない）")
                .hasSize(1);
        assertThat(workflowRequestStatus(requests.get(0))).isEqualTo("IN_PROGRESS");
        assertThat(alertWorkflowRequestId(alertId)).isEqualTo(requests.get(0));
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
        assertThat(failedEventRetryCount(eventId)).isEqualTo(2);
    }

    // ═══════════════════════════ 試練30 ═══════════════════════════

    @Test
    @DisplayName("試練30 THRESHOLD_ALERT の手動再試行は訂正後消化額（訂正なしは max(消化額, 確定額)）で判定し 100% 警告と起票を行う")
    void 手動再試行は訂正後消化額で判定する() throws Exception {
        Long templateId = seedWorkflowTemplate(false);
        configureOverLimitWorkflow(templateId);
        // 消化額キャッシュ 9,000 円（90%）だが確定額 10,050 円 → 訂正後消化額 10,050 円（100.5%）
        Long allocationId = seedAllocation("10000", "9000", "10050");
        Long eventId = seedFailedEvent(ShiftBudgetFailedEventType.THRESHOLD_ALERT, allocationId,
                "{\"allocation_id\":" + allocationId + "}");

        int status = manualRetry(eventId);

        assertThat(status).isEqualTo(200);
        assertThat(alertThresholds(allocationId))
                .as("消化額キャッシュだけで判定すると 90% で 80% 警告しか出ない")
                .containsExactly(80, 100);
        Long alert100 = alertId(allocationId, 100);
        List<Long> requests = liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alert100);
        assertThat(requests).hasSize(1);
        assertThat(alertWorkflowRequestId(alert100)).isEqualTo(requests.get(0));
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("試練30 THRESHOLD_ALERT のバッチ再試行も訂正後消化額で判定する")
    void バッチ再試行は訂正後消化額で判定する() {
        Long templateId = seedWorkflowTemplate(false);
        configureOverLimitWorkflow(templateId);
        Long allocationId = seedAllocation("10000", "9000", "10050");
        Long eventId = seedFailedEvent(ShiftBudgetFailedEventType.THRESHOLD_ALERT, allocationId,
                "{\"allocation_id\":" + allocationId + "}");

        retryBatchJob.run();

        assertThat(alertThresholds(allocationId)).containsExactly(80, 100);
        Long alert100 = alertId(allocationId, 100);
        assertThat(liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alert100)).hasSize(1);
        assertThat(failedEventStatus(eventId)).isEqualTo("SUCCEEDED");
    }

    // ═══════════════════════════ 試練31 ═══════════════════════════

    @Test
    @DisplayName("試練31 同じ alert の WORKFLOW_START を手動 2 本が同時に再試行しても、申請は 1 件で書き戻し先が一致する")
    void 手動2本の同時再試行でも申請は1件() throws Exception {
        Long templateId = seedWorkflowTemplate(false);
        configureOverLimitWorkflow(templateId);
        Long allocationId = seedAllocation("10000", "10000", "0");
        seedAlert(allocationId, 80, "10000");
        Long alertId = seedAlert(allocationId, 100, "10000");
        Long eventId = seedWorkflowStartEvent(allocationId, alertId, templateId);

        List<Integer> statuses = runConcurrently(() -> manualRetry(eventId), () -> manualRetry(eventId));

        assertThat(statuses).as("同時着手の一方は 409（実行中）で、500 等は出ない").allMatch(s -> s == 200 || s == 409);
        assertThat(statuses).contains(200);
        List<Long> requests = liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alertId);
        assertThat(requests).as("同じ alert に申請が 2 件立ってはならない").hasSize(1);
        assertThat(alertWorkflowRequestId(alertId)).isEqualTo(requests.get(0));
        assertThat(workflowRequestStepCount(requests.get(0))).isEqualTo(1);
    }

    @Test
    @DisplayName("試練31 同じ alert を指す 2 件の WORKFLOW_START をバッチと手動が同時に再試行しても、申請は 1 件で書き戻し先が一致する")
    void バッチと手動の同時再試行でも申請は1件() throws Exception {
        Long templateId = seedWorkflowTemplate(false);
        configureOverLimitWorkflow(templateId);
        Long allocationId = seedAllocation("10000", "10000", "0");
        seedAlert(allocationId, 80, "10000");
        Long alertId = seedAlert(allocationId, 100, "10000");
        Long batchEvent = seedWorkflowStartEvent(allocationId, alertId, templateId);
        Long manualEvent = seedWorkflowStartEvent(allocationId, alertId, templateId);

        List<Integer> statuses = runConcurrently(
                () -> {
                    retryBatchJob.run();
                    return 200;
                },
                () -> manualRetry(manualEvent));

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        List<Long> requests = liveWorkflowRequestIds(ALERT_SOURCE_TYPE, alertId);
        assertThat(requests).as("別々の失敗イベントでも同じ alert（source）への起票は 1 件に収束する").hasSize(1);
        assertThat(alertWorkflowRequestId(alertId)).isEqualTo(requests.get(0));
        assertThat(workflowRequestStatus(requests.get(0))).isEqualTo("IN_PROGRESS");
        assertThat(failedEventStatus(batchEvent)).isIn("SUCCEEDED", "PENDING", "RETRYING");
    }

    // ───────────── ヘルパー ─────────────

    private Long seedWorkflowStartEvent(Long allocationId, Long alertId, Long templateId) {
        return seedFailedEvent(ShiftBudgetFailedEventType.WORKFLOW_START, alertId,
                "{\"allocation_id\":" + allocationId + ",\"alert_id\":" + alertId
                        + ",\"workflow_id\":" + templateId + "}");
    }

    @FunctionalInterface
    private interface StatusCall {
        int call() throws Exception;
    }

    /** 2 つの呼び出しを CyclicBarrier で同時に走らせ、HTTP ステータス（例外は -1）を返す。 */
    private List<Integer> runConcurrently(StatusCall left, StatusCall right) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        Thread a = new Thread(() -> statuses.add(callAfter(start, left)), "cmp2152-wf-A");
        Thread b = new Thread(() -> statuses.add(callAfter(start, right)), "cmp2152-wf-B");
        a.start();
        b.start();
        a.join(120_000);
        b.join(120_000);
        return statuses;
    }

    private static int callAfter(CyclicBarrier start, StatusCall call) {
        try {
            start.await(30, TimeUnit.SECONDS);
            return call.call();
        } catch (Exception e) {
            return -1;
        }
    }
}
