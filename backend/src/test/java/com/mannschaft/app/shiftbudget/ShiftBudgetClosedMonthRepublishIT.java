package com.mannschaft.app.shiftbudget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * CMP-260909-2152 試練 35 — 締め済み月の再公開スキップと、ADD／締めとの並行を実 DB で検査する。
 *
 * <p>確定設計 r4-1（マスター御裁定で承認）: 締め後の計上は ADD の 1 経路のみ。
 * {@code ShiftBudgetConsumptionService} の消化記録は割当行 FOR UPDATE 後に締めイベント表 (c) を確認し、
 * 締め済み月なら PLANNED を作らずスキップする（ログ「締め済みのため訂正 API の ADD で計上」）。
 * 月次締めも同じ割当行をロックして (c) を INSERT するので直列になる。</p>
 * <ul>
 *   <li>AC10: ADD 後の締め月再公開で PLANNED 不作成・キャッシュ/summary 不変。</li>
 *   <li>AC10: ADD と再公開の並行で、どちらの順でも計上 1 件。</li>
 *   <li>AC10: 締めと消化記録の並行で、締め前分のみ CONFIRMED。</li>
 * </ul>
 */
@DisplayName("CMP-260909-2152 試練35 締め済み月の再公開スキップと並行（実DB）")
class ShiftBudgetClosedMonthRepublishIT extends ShiftBudgetCmp2152RealDbTestBase {

    private static final BigDecimal TOTAL = new BigDecimal("10000");

    @Autowired private MonthlyShiftBudgetCloseService closeService;
    @Autowired private ShiftBudgetConsumptionService consumptionService;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftSlotRepository slotRepository;
    @Autowired private ObjectMapper objectMapper;

    private Long teamId;
    private Long allocationId;
    private Long scheduleId;

    @Test
    @DisplayName("試練35 締め済み月の割当への再公開は PLANNED を作らず、消化額・確定額キャッシュも変えない")
    void 締め済み月の再公開はスキップする() {
        seedAllocationWithTwoPlanned();
        close();
        Long slotId = seedSlot(LocalDate.of(2026, 6, 15));

        consumptionService.recordSingleConsumption(allocationId, scheduleId, slotId, budgetAdminId,
                new BigDecimal("1200"), new BigDecimal("3"));

        assertThat(plannedCountForSlot(slotId))
                .as("締め後の PLANNED は永久に締められず、消化額だけが膨らむ（二重計上の入口）")
                .isZero();
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo(TOTAL);
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo(TOTAL);
    }

    @Test
    @DisplayName("試練35 ADD で締め後計上した勤務の再公開は PLANNED を作らず、summary も変わらない")
    void ADD後の再公開でsummaryが変わらない() throws Exception {
        seedAllocationWithTwoPlanned();
        close();
        Long slotId = seedSlot(LocalDate.of(2026, 6, 15));
        MvcResult add = postJson(budgetAdminId, correctionsPath(allocationId),
                addJson(UUID.randomUUID().toString(), 0, budgetAdminId, "2026-06-15", slotId, scheduleId,
                        "3", "1200", "CMP2152 試練35 締め後追加"));
        assertThat(add.getResponse().getStatus()).as("前提: 締め後追加が成立する").isIn(200, 201);
        JsonNode before = summaryData();
        BigDecimal consumedBefore = consumedAmount(allocationId);

        consumptionService.recordSingleConsumption(allocationId, scheduleId, slotId, budgetAdminId,
                new BigDecimal("1200"), new BigDecimal("3"));

        assertThat(plannedCountForSlot(slotId)).as("ADD 済みの勤務を再公開で二重に計上しない").isZero();
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo(consumedBefore);
        assertThat(summaryData()).as("summary（訂正後値）が再公開で動かない").isEqualTo(before);
    }

    @Test
    @DisplayName("試練35 ADD と再公開を同時に走らせても、計上は ADD の 1 件だけで PLANNED は作られない")
    void ADDと再公開の並行でも計上は1件() throws Exception {
        seedAllocationWithTwoPlanned();
        close();
        Long slotId = seedSlot(LocalDate.of(2026, 6, 16));
        String addBody = addJson(UUID.randomUUID().toString(), 0, budgetAdminId, "2026-06-16", slotId, scheduleId,
                "3", "1200", "CMP2152 試練35 並行");

        CyclicBarrier start = new CyclicBarrier(2);
        AtomicReference<Integer> addStatus = new AtomicReference<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread adder = new Thread(() -> {
            try {
                start.await(30, TimeUnit.SECONDS);
                addStatus.set(postJson(budgetAdminId, correctionsPath(allocationId), addBody).getResponse().getStatus());
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "cmp2152-add");
        Thread republisher = new Thread(() -> {
            try {
                start.await(30, TimeUnit.SECONDS);
                consumptionService.recordSingleConsumption(allocationId, scheduleId, slotId, budgetAdminId,
                        new BigDecimal("1200"), new BigDecimal("3"));
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "cmp2152-republish");
        adder.start();
        republisher.start();
        adder.join(60_000);
        republisher.join(60_000);

        assertThat(errors).isEmpty();
        assertThat(plannedCountForSlot(slotId))
                .as("再公開が先でも締め済み月なら PLANNED を作らない（作れば ADD が 409 PLANNED_EXISTS になり永久未計上）")
                .isZero();
        assertThat(addStatus.get()).as("締め後の計上は ADD の 1 経路で成立する").isIn(200, 201);
        assertThat(consumedAmount(allocationId)).as("消化額キャッシュは再公開で増えない").isEqualByComparingTo(TOTAL);
    }

    @Test
    @DisplayName("試練35 締めと消化記録が並行したら、締め前の分だけが CONFIRMED になり、締め後の記録は PLANNED を残さない")
    void 締めと消化記録の並行は締め前分のみ確定() throws Exception {
        seedAllocationWithTwoPlanned();
        Long lateSlot = seedSlot(LocalDate.of(2026, 6, 20));

        // 締めを「割当行の排他ロック取得直後（月次仕訳の重複確認）」で止める。金型: MonthlyShiftBudgetCloseTransactionIT。
        CountDownLatch closeInside = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger existsCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (existsCalls.getAndIncrement() == 0) {
                closeInside.countDown();
                releaseClose.await(30, TimeUnit.SECONDS);
                return false;
            }
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM budget_transactions WHERE source_type = ? "
                            + "AND source_id = ? AND deleted_at IS NULL", Integer.class,
                    MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY, allocationId);
            return n != null && n > 0;
        }).when(budgetTransactionRepository).existsBySourceTypeAndSourceIdAndTransactionDate(any(), any(), any());

        AtomicReference<Throwable> closeError = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            setAuth(budgetAdminId);
            try {
                closeService.close(orgId, TARGET_MONTH);
            } catch (Throwable t) {
                closeError.set(t);
            }
        }, "cmp2152-close");
        closer.start();
        assertThat(closeInside.await(30, TimeUnit.SECONDS)).as("締めが割当行ロックを握った状態を作れないと並行にならない").isTrue();

        AtomicReference<Throwable> recordError = new AtomicReference<>();
        Thread recorder = new Thread(() -> {
            try {
                consumptionService.recordSingleConsumption(allocationId, scheduleId, lateSlot, budgetAdminId,
                        new BigDecimal("1200"), new BigDecimal("2"));
            } catch (Throwable t) {
                recordError.set(t);
            }
        }, "cmp2152-record");
        recorder.start();
        Thread.sleep(1500); // 記録側が割当行ロック待ちに入るまで
        releaseClose.countDown();
        closer.join(60_000);
        recorder.join(60_000);

        assertThat(closeError.get()).isNull();
        assertThat(recordError.get()).as("締め済みのためのスキップは例外ではなくログで終える").isNull();
        assertThat(consumptionStatuses(allocationId))
                .as("締め前の 2 件だけが CONFIRMED。締め後の記録が PLANNED で残ると永久に締められない")
                .containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(plannedCountForSlot(lateSlot)).isZero();
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo(TOTAL);
        assertThat(consumedAmount(allocationId)).as("締め後の記録が消化額キャッシュへ足されていない").isEqualByComparingTo(TOTAL);
    }

    // ───────────── ヘルパー ─────────────

    private void seedAllocationWithTwoPlanned() {
        teamId = seedTeam();
        allocationId = seedTeamAllocation(teamId, "500000", "10000", "0");
        scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder().teamId(teamId).title("CMP2152 6月")
                .periodType(ShiftPeriodType.MONTHLY).startDate(PERIOD_START).endDate(PERIOD_END)
                .status(ShiftScheduleStatus.PUBLISHED).build()).getId();
        seedConsumption(allocationId, seedSlot(LocalDate.of(2026, 6, 1)), budgetAdminId, "1200", "4",
                ShiftBudgetConsumptionStatus.PLANNED);
        seedConsumption(allocationId, seedSlot(LocalDate.of(2026, 6, 2)), budgetAdminId, "1300", "4",
                ShiftBudgetConsumptionStatus.PLANNED);
    }

    private Long seedSlot(LocalDate date) {
        return slotRepository.save(ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).requiredCount(1).build()).getId();
    }

    private void close() {
        setAuth(budgetAdminId);
        assertThat(closeService.close(orgId, TARGET_MONTH).closedAllocations()).as("前提: 月次締めが成立").isEqualTo(1);
    }

    private JsonNode summaryData() throws Exception {
        MvcResult result = getAs(budgetAdminId, "/api/v1/shift-budget/allocations/" + allocationId + "/consumption-summary");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
