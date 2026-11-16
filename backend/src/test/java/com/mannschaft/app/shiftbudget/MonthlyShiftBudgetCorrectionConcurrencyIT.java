package com.mannschaft.app.shiftbudget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.budget.service.BudgetFiscalYearService;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 試練 5・20 — 締め後訂正の冪等・同時実行と、年度クローズとの並行を実 DB で検査する。
 *
 * <p>確定設計 r2「budget TX の順序」: 1 command 検索 → 2 年度行 findByIdForUpdate（OPEN でなければ BUDGET_004。
 * 年度クローズ側 UPDATE はこのロックを待つ）→ 3 原月次仕訳 FOR UPDATE → 4 command 再検索 → 5 締め記録 FOR UPDATE
 * → 6 expected_revision 照合 → 7 差額仕訳・訂正行 INSERT → 8 revision 更新。UNIQUE 違反時は読み直して保存結果を返す。</p>
 * <ul>
 *   <li>AC10追: 同 command 同時送信は両方同結果（履歴 1・仕訳 1）。同 command 異内容の同時送信は一方 409。</li>
 *   <li>AC6追: 年度クローズと訂正の同時実行で「CLOSED 年度内に訂正」状態にならない。</li>
 * </ul>
 * <p>締め後訂正 API は全段 HTTP（実 Security フィルタ・実 Controller）で叩く。リクエスト DTO の項目名は
 * 確定設計の語彙（command_id / expected_revision / kind / consumption_id / hours / hourly_rate / reason）に合わせた。</p>
 */
@DisplayName("CMP-260909-2152 試練5・20 締め後訂正の冪等・同時実行と年度クローズ並行（実DB）")
class MonthlyShiftBudgetCorrectionConcurrencyIT extends ShiftBudgetCmp2152RealDbTestBase {

    @Autowired private MonthlyShiftBudgetCloseService closeService;
    @Autowired private BudgetFiscalYearService fiscalYearService;
    @Autowired private ObjectMapper objectMapper;

    private Long allocationId;
    private Long consumptionA;

    // ═══════════════════════════ 試練5 ═══════════════════════════

    @Test
    @DisplayName("試練5 同じ command_id・同じ内容を 2 スレッドが同時に送ると、両方が同じ結果を受け取り履歴も差額仕訳も 1 件")
    void 同command同内容の同時送信は同結果() throws Exception {
        seedClosedMonth();
        String commandId = UUID.randomUUID().toString();
        String body = modifyJson(commandId, 0, consumptionA, "3", "1200", "CMP2152 試練5 同時送信");

        List<MvcResult> results = sendConcurrently(body, body);

        assertThat(results).extracting(r -> r.getResponse().getStatus())
                .as("後着は UNIQUE(command_id) 違反を読み直して保存結果を返す（500 や 409 にしない）")
                .allMatch(s -> s == 200 || s == 201);
        assertThat(data(results.get(0))).as("両者が同じ訂正結果を受け取る").isEqualTo(data(results.get(1)));
        assertThat(correctionTransactionCount(allocationId)).as("差額仕訳が二重に立つと会計金額がずれる").isEqualTo(1);
        assertThat(historySize()).as("訂正履歴は 1 件").isEqualTo(1);
    }

    @Test
    @DisplayName("試練5 同じ command_id で内容の違う 2 本を同時に送ると、一方だけが成立し他方は 409")
    void 同command異内容の同時送信は一方409() throws Exception {
        seedClosedMonth();
        String commandId = UUID.randomUUID().toString();
        String bodyA = modifyJson(commandId, 0, consumptionA, "3", "1200", "CMP2152 試練5 内容A");
        String bodyB = modifyJson(commandId, 0, consumptionA, "2", "1200", "CMP2152 試練5 内容B");

        List<MvcResult> results = sendConcurrently(bodyA, bodyB);

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).as("成立 1・競合 409 が 1").containsExactlyInAnyOrder(statuses.contains(201) ? 201 : 200, 409);
        assertThat(correctionTransactionCount(allocationId)).isEqualTo(1);
        assertThat(historySize()).isEqualTo(1);
    }

    // ═══════════════════════════ 試練20 ═══════════════════════════

    @Test
    @DisplayName("試練20 訂正が年度行をロックしている間、年度クローズは待たされ、訂正の確定後にクローズされる（CLOSED 年度内の訂正にならない）")
    void 年度クローズは訂正の確定を待つ() throws Exception {
        seedClosedMonth();
        String body = modifyJson(UUID.randomUUID().toString(), 0, consumptionA, "3", "1200", "CMP2152 試練20");

        // 訂正 TX を「年度行ロック取得後・原月次仕訳ロック待ち」で止めるため、別接続で原月次仕訳を FOR UPDATE で握る。
        // 設計の順序（年度 → 原月次仕訳）どおりなら、訂正は年度行を握ったままここで待つ。
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM budget_transactions WHERE source_type = ? AND source_id = ? "
                            + "AND deleted_at IS NULL FOR UPDATE", Long.class,
                    MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY, allocationId);
            holding.countDown();
            try {
                releaseHolder.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), "cmp2152-holder");
        holder.start();
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Integer> correctionStatus = new AtomicReference<>();
        AtomicLong correctionDoneAt = new AtomicLong();
        Thread correction = new Thread(() -> {
            try {
                correctionStatus.set(postJson(budgetAdminId, correctionsPath(allocationId), body).getResponse().getStatus());
            } catch (Exception e) {
                correctionStatus.set(-1);
            } finally {
                correctionDoneAt.set(System.nanoTime());
            }
        }, "cmp2152-correction");
        correction.start();
        Thread.sleep(1500); // 訂正が年度行を握って原月次仕訳のロック待ちに入るまで

        AtomicReference<Throwable> closeError = new AtomicReference<>();
        AtomicLong closeDoneAt = new AtomicLong();
        CountDownLatch closeDone = new CountDownLatch(1);
        Thread close = new Thread(() -> {
            setAuth(budgetAdminId);
            try {
                fiscalYearService.close(fiscalYearId);
            } catch (Throwable t) {
                closeError.set(t);
            } finally {
                closeDoneAt.set(System.nanoTime());
                closeDone.countDown();
            }
        }, "cmp2152-fy-close");
        close.start();
        boolean closeFinishedWhileCorrectionOpen = closeDone.await(3, TimeUnit.SECONDS);

        releaseHolder.countDown();
        holder.join(60_000);
        correction.join(60_000);
        close.join(60_000);

        assertThat(correctionStatus.get()).as("年度が OPEN の間に始まった訂正は成立する").isIn(200, 201);
        assertThat(closeFinishedWhileCorrectionOpen)
                .as("年度行ロックが無いと、クローズは訂正の確定前に終わり、その後に訂正が CLOSED 年度へ書かれる")
                .isFalse();
        assertThat(closeError.get()).as("クローズ自体は訂正の確定後に成立する").isNull();
        assertThat(closeDoneAt.get()).as("クローズの確定は訂正の確定より後").isGreaterThan(correctionDoneAt.get());
        assertThat(fiscalYearStatus()).isEqualTo("CLOSED");
        assertThat(correctionTransactionCount(allocationId)).isEqualTo(1);
    }

    // ───────────── ヘルパー ─────────────

    /** 2 件の PLANNED（4,800 円・5,200 円）を持つ割当を作り、2026-06 を実サービスで締める。 */
    private void seedClosedMonth() {
        Long teamId = seedTeam();
        allocationId = seedTeamAllocation(teamId, "500000", "10000", "0");
        consumptionA = seedConsumption(allocationId, nextId(), budgetAdminId, "1200", "4", ShiftBudgetConsumptionStatus.PLANNED);
        seedConsumption(allocationId, nextId(), budgetAdminId, "1300", "4", ShiftBudgetConsumptionStatus.PLANNED);
        setAuth(budgetAdminId);
        MonthlyShiftBudgetCloseService.CloseResult result = closeService.close(orgId, TARGET_MONTH);
        assertThat(result.closedAllocations()).as("前提: 月次締めが成立している").isEqualTo(1);
    }

    private List<MvcResult> sendConcurrently(String left, String right) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(2);
        List<MvcResult> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable a = () -> send(start, left, results, errors);
        Runnable b = () -> send(start, right, results, errors);
        Thread ta = new Thread(a, "cmp2152-corr-A");
        Thread tb = new Thread(b, "cmp2152-corr-B");
        ta.start();
        tb.start();
        ta.join(60_000);
        tb.join(60_000);
        assertThat(errors).isEmpty();
        assertThat(results).hasSize(2);
        return results;
    }

    private void send(CyclicBarrier start, String body, List<MvcResult> results, List<Throwable> errors) {
        try {
            start.await(30, TimeUnit.SECONDS);
            results.add(postJson(budgetAdminId, correctionsPath(allocationId), body));
        } catch (Throwable t) {
            errors.add(t);
        }
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** 履歴 GET（BUDGET_VIEW）の件数。ページ形（items / content）と配列の両方を受ける。 */
    private int historySize() throws Exception {
        MvcResult result = getAs(budgetAdminId, correctionsPath(allocationId));
        assertThat(result.getResponse().getStatus()).as("履歴 GET が取れること").isEqualTo(200);
        JsonNode data = data(result);
        if (data.isArray()) {
            return data.size();
        }
        for (String field : List.of("items", "content", "corrections")) {
            if (data.path(field).isArray()) {
                return data.path(field).size();
            }
        }
        throw new AssertionError("履歴 GET の data に一覧が無い: " + data);
    }

    private String fiscalYearStatus() {
        return jdbc.queryForObject("SELECT status FROM budget_fiscal_years WHERE id = ?", String.class, fiscalYearId);
    }
}
