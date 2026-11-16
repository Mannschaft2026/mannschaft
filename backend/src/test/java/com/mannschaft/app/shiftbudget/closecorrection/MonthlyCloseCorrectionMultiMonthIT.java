package com.mannschaft.app.shiftbudget.closecorrection;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 試練（AC22 複数月にまたがる割当）。
 *
 * <p>月次締めは勤務枠の月に関係なく割当内の全 PLANNED を CONFIRMED にするため、4〜6月の割当では
 * 2回目以降の締めの明細を「確定明細の全部」からは特定できない。締めた明細の集合を
 * {@code shift_budget_consumptions.close_event_id} で永続化し、訂正は当該締めの明細だけを対象にする。
 * 印の無い旧締めが2回ある割当は月を特定できないため BUDGET_033（409）で、変更は0。
 * 実装前は 4 月の訂正が全月の確定明細の合計と原仕訳の不一致で BUDGET_033 になり red。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 複数月の割当の締め後訂正（実DB・実HTTP）")
class MonthlyCloseCorrectionMultiMonthIT extends MonthlyCloseCorrectionTestBase {

    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final YearMonth MAY = YearMonth.of(2026, 5);

    /** 4/1〜6/30 の割当（キャッシュは 0）。 */
    private Long quarterAllocation(String allocated) {
        Long allocationId = allocation(allocated);
        jdbc.update("UPDATE shift_budget_allocations SET period_start = ?, period_end = ? WHERE id = ?",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), allocationId);
        return allocationId;
    }

    private void closeMonth(YearMonth month) {
        asUser(adminId, () -> closeService.close(orgId, month));
    }

    private int recordCount(Long allocationId, String month) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM budget_monthly_close_records "
                + "WHERE allocation_id = ? AND target_month = ?", Integer.class, allocationId, month);
        return n == null ? 0 : n;
    }

    private int correctionCount(Long allocationId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM budget_monthly_close_corrections c "
                + "JOIN budget_monthly_close_records r ON r.id = c.close_record_id WHERE r.allocation_id = ?",
                Integer.class, allocationId);
        return n == null ? 0 : n;
    }

    private org.springframework.test.web.servlet.ResultActions correct(Long allocationId, String month,
                                                                       Map<String, Object> body) throws Exception {
        return postCorrection(adminId, orgId, allocationId, month, body);
    }

    @Test
    @DisplayName("試練42 AC22 4〜6月の割当で4月・5月を締め、4月の締めの明細だけを訂正できる。5月の締めは不変")
    void 試練42_四月五月を締め四月の明細だけを訂正できる() throws Exception {
        Long allocationId = quarterAllocation("90000");
        Long aprilA = planned(allocationId, workerId, "1200", "4");
        planned(allocationId, workerId, "1300", "4");
        closeMonth(APRIL);
        Long mayLine = planned(allocationId, workerId, "1500", "2");
        closeMonth(MAY);
        assertThat(journals(allocationId, SOURCE_MONTHLY)).hasSize(2);

        List<Map<String, Object>> consumptionsBefore = consumptionRows(allocationId);
        List<Map<String, Object>> originalBefore = journals(allocationId, SOURCE_MONTHLY);
        assertThat(recordCount(allocationId, "2026-05")).isZero();

        correct(allocationId, "2026-04", modify(aprilA, "1.5", "1200", 0, "4月の締めの訂正"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("MODIFY"))
                .andExpect(jsonPath("$.data.revision").value(1));

        assertThat(recordCount(allocationId, "2026-04")).isEqualTo(1);
        assertThat(recordCount(allocationId, "2026-05")).as("5月の締め記録は作られない").isZero();
        assertThat(consumptionRows(allocationId)).as("元消化行は不変").isEqualTo(consumptionsBefore);
        assertThat(journals(allocationId, SOURCE_MONTHLY)).as("5月を含む原月次仕訳は不変").isEqualTo(originalBefore);
        List<Map<String, Object>> deltas = journals(allocationId, SOURCE_CORRECTION);
        assertThat(deltas).hasSize(1);
        assertThat((BigDecimal) deltas.get(0).get("amount")).isEqualByComparingTo("-3000");
        assertThat(deltas.get(0).get("transaction_date").toString()).isEqualTo("2026-04-30");

        // 4月の締めの明細ではない5月の明細は、4月の訂正の対象にならない（変更0）
        int correctionsBefore = correctionCount(allocationId);
        correct(allocationId, "2026-04", modify(mayLine, "1", "1500", 1, "5月の明細を4月で訂正"))
                .andExpect(status().is4xxClientError());
        assertThat(correctionCount(allocationId)).isEqualTo(correctionsBefore);
        assertThat(journals(allocationId, SOURCE_CORRECTION)).hasSize(1);

        // 5月の明細は5月の締めの訂正として通る
        correct(allocationId, "2026-05", modify(mayLine, "1", "1500", 0, "5月の締めの訂正"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revision").value(1));
        assertThat(recordCount(allocationId, "2026-05")).isEqualTo(1);
    }

    @Test
    @DisplayName("試練43 AC22 4/30 の勤務が5月の締めで確定した場合、5月の締めの明細として訂正できる")
    void 試練43_四月末の勤務が五月の締めで確定したら五月の明細として訂正できる() throws Exception {
        Long allocationId = quarterAllocation("90000");
        planned(allocationId, workerId, "1200", "4");
        closeMonth(APRIL);
        long[] slot = publishedSlot(teamId, LocalDate.of(2026, 4, 30), LocalTime.of(20, 0), LocalTime.of(22, 0),
                workerId);
        Long late = consumption(allocationId, slot[1], workerId, "1000", "2", ShiftBudgetConsumptionStatus.PLANNED);
        closeMonth(MAY);
        List<Map<String, Object>> originalBefore = journals(allocationId, SOURCE_MONTHLY);
        assertThat(originalBefore).hasSize(2);

        correct(allocationId, "2026-05", modify(late, "1", "1000", 0, "4/30 勤務の5月の締めでの訂正"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("MODIFY"));

        assertThat(recordCount(allocationId, "2026-05")).isEqualTo(1);
        assertThat(recordCount(allocationId, "2026-04")).as("4月の締め記録は作られない").isZero();
        assertThat(journals(allocationId, SOURCE_MONTHLY)).isEqualTo(originalBefore);
        List<Map<String, Object>> deltas = journals(allocationId, SOURCE_CORRECTION);
        assertThat(deltas).hasSize(1);
        assertThat((BigDecimal) deltas.get(0).get("amount")).isEqualByComparingTo("-1000");
        assertThat(deltas.get(0).get("transaction_date").toString()).isEqualTo("2026-05-31");
    }

    @Test
    @DisplayName("試練44 AC22 印の無い旧締めが2回ある割当は BUDGET_033（409）で、仕訳・訂正・締め記録・消化行の変更は0")
    void 試練44_印の無い旧締めが2回ある割当は月を特定できず拒否される() throws Exception {
        Long allocationId = quarterAllocation("90000");
        Long aprilLine = consumption(allocationId, nextSlot(), workerId, "1200", "4",
                ShiftBudgetConsumptionStatus.CONFIRMED);
        consumption(allocationId, nextSlot(), workerId, "1300", "4", ShiftBudgetConsumptionStatus.CONFIRMED);
        legacyJournal(allocationId, LocalDate.of(2026, 4, 30), "4800");
        legacyJournal(allocationId, LocalDate.of(2026, 5, 31), "5200");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_consumptions WHERE allocation_id = ? "
                + "AND close_event_id IS NULL", Integer.class, allocationId)).isEqualTo(2);

        List<Map<String, Object>> consumptionsBefore = consumptionRows(allocationId);
        List<Map<String, Object>> originalBefore = journals(allocationId, SOURCE_MONTHLY);
        int journalCountBefore = budgetTransactionCount();

        correct(allocationId, "2026-04", modify(aprilLine, "1.5", "1200", 0, "旧締めの訂正"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUDGET_033"));
        correct(allocationId, "2026-05", modify(aprilLine, "1.5", "1200", 0, "旧締めの訂正"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUDGET_033"));

        assertThat(recordCount(allocationId, "2026-04")).isZero();
        assertThat(recordCount(allocationId, "2026-05")).isZero();
        assertThat(correctionCount(allocationId)).isZero();
        assertThat(journals(allocationId, SOURCE_CORRECTION)).isEmpty();
        assertThat(journals(allocationId, SOURCE_MONTHLY)).isEqualTo(originalBefore);
        assertThat(budgetTransactionCount()).isEqualTo(journalCountBefore);
        assertThat(consumptionRows(allocationId)).isEqualTo(consumptionsBefore);
    }

    /** 締めイベント導入前の締め（原月次仕訳だけがあり、締めイベントも消化行の印も無い）を作る。 */
    private void legacyJournal(Long allocationId, LocalDate date, String amount) {
        tx.executeWithoutResult(s -> em.persist(BudgetTransactionEntity.builder().fiscalYearId(fiscalYearId)
                .categoryId(categoryId).scopeType("TEAM").scopeId(teamId)
                .transactionType(BudgetTransactionType.EXPENSE).amount(new BigDecimal(amount)).transactionDate(date)
                .title(date.getYear() + "年" + date.getMonthValue() + "月 シフト人件費（自動集計）")
                .isAutoRecorded(true).sourceType(SOURCE_MONTHLY).sourceId(allocationId)
                .approvalStatus(BudgetApprovalStatus.APPROVED).recordedBy(adminId).build()));
    }
}
