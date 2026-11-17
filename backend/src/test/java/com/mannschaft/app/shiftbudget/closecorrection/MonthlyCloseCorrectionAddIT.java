package com.mannschaft.app.shiftbudget.closecorrection;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 試練（締め後の追加計上 ADD）: 25〜29・34・36。
 *
 * <p>マスター御裁定1: 締め後の漏れ勤務は ADD として budget 訂正表の行でのみ持ち、shiftbudget 消化行は作らない。
 * 一度締めた旨（元額・締め日時・操作者区分）と追加分を区別して表示する。実装前は ADD が 500 を返すため、
 * 最初の HTTP ステータスで意図どおり red になる（割当削除の試練29は既存 API が 204 を返して red）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 月次締め後の追加計上（実DB・実HTTP）")
class MonthlyCloseCorrectionAddIT extends MonthlyCloseCorrectionTestBase {

    private static final LocalDate JUNE_15 = LocalDate.of(2026, 6, 15);

    private Long closedTenThousand() {
        Long allocationId = allocation("50000");
        planned(allocationId, workerId, "1200", "4");
        planned(allocationId, workerId, "1300", "4");
        closeJune();
        return allocationId;
    }

    private int correctionJournalCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM budget_transactions WHERE source_type = ? "
                + "AND fiscal_year_id = ?", Integer.class, SOURCE_CORRECTION, fiscalYearId);
        return n == null ? 0 : n;
    }

    // ───────────── 試練25（AC16） ─────────────

    @Test
    @DisplayName("試練25 10000円締め月へ3000円 ADD で当月13000円・翌月0円、原仕訳・元消化は不変、締め事実と追加分を区別表示")
    void 試練25_締め後追加で13000円() throws Exception {
        Long allocationId = closedTenThousand();
        List<Map<String, Object>> consumptionsBefore = consumptionRows(allocationId);
        List<Map<String, Object>> originalBefore = journals(allocationId, SOURCE_MONTHLY);

        JsonNode c = data(postCorrection(adminId, allocationId,
                add(workerId, JUNE_15, null, null, "2.5", "1200", 0, "締め後に判明した応援勤務"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("ADD"))
                .andExpect(jsonPath("$.data.line_kind").value("ADDED")));
        assertThat(c.path("added_line_id").isTextual()).as("ADD 行自身の ID で明細を特定する").isTrue();
        assertNumber(c, "before_amount", "0");
        assertNumber(c, "after_amount", "3000");
        assertNumber(c, "posted_delta", "3000");

        assertAllocationAmounts(adminId, allocationId, "13000", "13000", "3000");
        assertThat(consumptionRows(allocationId)).as("shiftbudget 消化行は新設しない").isEqualTo(consumptionsBefore);
        assertThat(journals(allocationId, SOURCE_MONTHLY)).isEqualTo(originalBefore);
        List<Map<String, Object>> deltas = journals(allocationId, SOURCE_CORRECTION);
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).get("transaction_date").toString()).as("翌月へは計上しない").isEqualTo("2026-06-30");

        JsonNode close = data(history(adminId, allocationId).andExpect(status().isOk())).path("close");
        assertNumber(close, "original_amount", "10000");
        assertThat(close.path("original_line_count").asInt()).isEqualTo(2);
        assertThat(close.path("close_actor_kind").asText()).isEqualTo("MANUAL");
        assertThat(close.path("closed_at").isTextual()).isTrue();
        assertThat(close.path("added_count").asInt()).isEqualTo(1);
        assertNumber(close, "added_amount", "3000");

        JsonNode s = data(summary(adminId, allocationId).andExpect(status().isOk()));
        JsonNode row = s.path("by_user").get(0);
        assertNumber(row, "amount", "13000");
        assertThat(row.path("added_count").asInt()).as("個人別に締め後追加の件数を分けて返す").isEqualTo(1);
        assertNumber(row, "added_amount", "3000");
    }

    // ───────────── 試練26（AC17） ─────────────

    @Test
    @DisplayName("試練26 0円締め月への ADD が成功し、締め記録は0件0円を保持する")
    void 試練26_0円締め月へのADD() throws Exception {
        Long allocationId = allocation("50000");
        closeJune();

        postCorrection(adminId, allocationId, add(workerId, JUNE_15, null, null, "2.5", "1200", 0, "締め後追加"))
                .andExpect(status().isCreated());

        assertAllocationAmounts(adminId, allocationId, "3000", "3000", "3000");
        JsonNode close = data(history(adminId, allocationId).andExpect(status().isOk())).path("close");
        assertNumber(close, "original_amount", "0");
        assertThat(close.path("original_line_count").asInt()).isZero();
        assertNumber(close, "added_amount", "3000");
    }

    // ───────────── 試練27（AC18） ─────────────

    @Test
    @DisplayName("試練27 ADD の時給 null/0/負・時間の範囲外は400、組織外の利用者は404、同slot同人の重複は409。いずれも変更0")
    void 試練27_ADDの入力検証と重複() throws Exception {
        Long allocationId = allocation("50000");
        long[] confirmedSlot = publishedSlot(teamId, LocalDate.of(2026, 6, 10), LocalTime.of(9, 0), LocalTime.of(13, 0),
                workerId);
        consumption(allocationId, confirmedSlot[1], workerId, "1200", "4", ShiftBudgetConsumptionStatus.PLANNED);
        long[] freeSlot = publishedSlot(teamId, LocalDate.of(2026, 6, 11), LocalTime.of(9, 0), LocalTime.of(13, 0),
                workerId);
        closeJune();
        Long outsider = newUser();
        List<Map<String, Object>> consumptionsBefore = consumptionRows(allocationId);
        int journalsBefore = correctionJournalCount();

        postCorrection(adminId, allocationId, add(workerId, JUNE_15, null, null, "2", null, 0, "時給なし"))
                .andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(workerId, JUNE_15, null, null, "2", "0", 0, "時給0"))
                .andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(workerId, JUNE_15, null, null, "2", "-1", 0, "負の時給"))
                .andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(workerId, JUNE_15, null, null, "0", "1200", 0, "0時間"))
                .andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(workerId, JUNE_15, null, null, "24.01", "1200", 0, "1日超"))
                .andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 7, 1), null, null, "2", "1200", 0,
                "対象月外")).andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(outsider, JUNE_15, null, null, "2", "1200", 0, "組織外の利用者"))
                .andExpect(status().isNotFound());
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 10), confirmedSlot[1],
                confirmedSlot[0], "4", "1200", 0, "CONFIRMED と重複")).andExpect(status().isConflict());
        assertThat(correctionJournalCount()).isEqualTo(journalsBefore);

        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 11), freeSlot[1], freeSlot[0],
                "4", "1200", 0, "漏れ勤務の追加")).andExpect(status().isCreated());
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 11), freeSlot[1], freeSlot[0],
                "4", "1200", 1, "生存 ADD と重複")).andExpect(status().isConflict());

        assertThat(consumptionRows(allocationId)).isEqualTo(consumptionsBefore);
        assertAllocationAmounts(adminId, allocationId, "9600", "9600", "4800");
    }

    // ───────────── 試練28（AC19） ─────────────

    @Test
    @DisplayName("試練28 追加明細の修正→取消→取消の取消ができ、最新でない訂正の取消は409")
    void 試練28_追加明細の訂正連鎖() throws Exception {
        Long allocationId = closedTenThousand();
        JsonNode added = data(postCorrection(adminId, allocationId,
                add(workerId, JUNE_15, null, null, "2", "1200", 0, "締め後追加")).andExpect(status().isCreated()));
        String lineId = added.path("added_line_id").asText();
        assertAllocationAmounts(adminId, allocationId, "12400", "12400", "2400");

        String modifyId = data(postCorrection(adminId, allocationId, modifyAdded(lineId, "3", "1200", 1, "3時間だった"))
                .andExpect(status().isCreated())).path("id").asText();
        assertAllocationAmounts(adminId, allocationId, "13600", "13600", "3600");

        String cancelId = data(postCorrection(adminId, allocationId, cancelAdded(lineId, 2, "勤務は無かった"))
                .andExpect(status().isCreated())).path("id").asText();
        assertAllocationAmounts(adminId, allocationId, "10000", "10000", "0");

        postReversal(adminId, allocationId, modifyId, 3, "最新でない訂正の取消").andExpect(status().isConflict());
        postReversal(adminId, allocationId, cancelId, 3, "取消は誤り").andExpect(status().isCreated());
        assertAllocationAmounts(adminId, allocationId, "13600", "13600", "3600");
    }

    // ───────────── 試練29（AC20） ─────────────

    @Test
    @DisplayName("試練29 締め済み月（0件締め・ADD のみの月・締めイベント以前の旧締め）を持つ割当の削除は409、未締めは削除できる")
    void 試練29_締め済み月を持つ割当は削除できない() throws Exception {
        Long zeroClosed = allocation("50000");
        Long addOnly = allocation("50000");
        closeJune();
        postCorrection(adminId, addOnly, add(workerId, JUNE_15, null, null, "2", "1200", 0, "締め後追加"))
                .andExpect(status().isCreated());

        Long legacyFy = tx.execute(s -> insertFiscalYear(orgId, adminId));
        Long legacyCategory = tx.execute(s -> insertCategory(legacyFy));
        Long legacy = allocation(orgId, teamId, legacyFy, legacyCategory, "50000");
        tx.executeWithoutResult(s -> em.persist(BudgetTransactionEntity.builder().fiscalYearId(legacyFy)
                .categoryId(legacyCategory).scopeType("TEAM").scopeId(teamId)
                .transactionType(BudgetTransactionType.EXPENSE).amount(BigDecimal.ZERO).transactionDate(JUNE_END)
                .title("2026年6月 シフト人件費（自動集計）").isAutoRecorded(true).sourceType(SOURCE_MONTHLY)
                .sourceId(legacy).approvalStatus(BudgetApprovalStatus.APPROVED).recordedBy(adminId).build()));
        Long controlFy = tx.execute(s -> insertFiscalYear(orgId, adminId));
        Long control = allocation(orgId, teamId, controlFy, tx.execute(s -> insertCategory(controlFy)), "50000");

        deleteAllocation(adminId, zeroClosed).andExpect(status().isConflict());
        deleteAllocation(adminId, addOnly).andExpect(status().isConflict());
        deleteAllocation(adminId, legacy).andExpect(status().isConflict());
        deleteAllocation(adminId, control).andExpect(status().isNoContent());

        for (Long kept : List.of(zeroClosed, addOnly, legacy)) {
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM shift_budget_allocations WHERE id = ?",
                    Object.class, kept)).as("締め済み月を持つ割当は論理削除もされない").isNull();
        }
    }

    // ───────────── 試練34（r4-1） ─────────────

    @Test
    @DisplayName("試練34 同slot同人の PLANNED があれば ADD は409、他割当のスロットへの ADD は404。いずれも変更0")
    void 試練34_PLANNED存在と他割当スロット() throws Exception {
        Long allocationId = allocation("50000");
        long[] slot = publishedSlot(teamId, LocalDate.of(2026, 6, 12), LocalTime.of(9, 0), LocalTime.of(13, 0), workerId);
        Long otherTeam = tx.execute(s -> {
            Long t = insertTeam();
            insertTeamOrgMembership(t, orgId);
            return t;
        });
        Long otherFy = tx.execute(s -> insertFiscalYear(orgId, adminId));
        allocation(orgId, otherTeam, otherFy, tx.execute(s -> insertCategory(otherFy)), "50000");
        long[] foreignSlot = publishedSlot(otherTeam, LocalDate.of(2026, 6, 12), LocalTime.of(9, 0),
                LocalTime.of(13, 0), workerId);
        closeJune();
        consumption(allocationId, slot[1], workerId, "1200", "4", ShiftBudgetConsumptionStatus.PLANNED);
        int journalsBefore = correctionJournalCount();

        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 12), slot[1], slot[0], "4", "1200", 0,
                "PLANNED と重複")).andExpect(status().isConflict());
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 12), foreignSlot[1], foreignSlot[0],
                "4", "1200", 0, "他割当のスロット")).andExpect(status().isNotFound());

        assertThat(correctionJournalCount()).isEqualTo(journalsBefore);
        history(adminId, allocationId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ───────────── 試練42（検分指摘3: 勤務枠の日付の検証） ─────────────

    @Test
    @DisplayName("試練45 勤務枠の実日付が要求の勤務日と違えば400、割当期間外の枠は404。いずれも変更0")
    void 試練45_勤務枠の日付と期間の検証() throws Exception {
        Long allocationId = allocation("50000");
        // 6月の枠（6/12）。勤務日を 6/20 と偽った ADD は二重計上の入口になるため拒否する
        long[] june = publishedSlot(teamId, LocalDate.of(2026, 6, 12), LocalTime.of(9, 0), LocalTime.of(13, 0), workerId);
        // 同じチームの割当期間外（7月）の枠。6月の割当へは追加できない
        long[] july = publishedSlot(teamId, LocalDate.of(2026, 7, 5), LocalTime.of(9, 0), LocalTime.of(13, 0), workerId);
        closeJune();
        int journalsBefore = correctionJournalCount();

        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 20), june[1], june[0], "4", "1200", 0,
                "勤務日の偽り")).andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 12), july[1], july[0], "4", "1200", 0,
                "期間外の枠")).andExpect(status().isNotFound());

        assertThat(correctionJournalCount()).isEqualTo(journalsBefore);
        history(adminId, allocationId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        // 正しい日付なら追加できる
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 12), june[1], june[0], "4", "1200", 0,
                "正しい追加")).andExpect(status().isCreated());
    }

    // ───────────── 試練36（r4-2 復活の重複） ─────────────

    @Test
    @DisplayName("試練36 原明細取消→同slot同人ADD→原明細の取消の取消は409")
    void 試練36_原明細の復活が重複する() throws Exception {
        Long allocationId = allocation("50000");
        long[] slot = publishedSlot(teamId, LocalDate.of(2026, 6, 12), LocalTime.of(9, 0), LocalTime.of(13, 0), workerId);
        Long line = consumption(allocationId, slot[1], workerId, "1200", "4", ShiftBudgetConsumptionStatus.PLANNED);
        closeJune();

        String cancelId = data(postCorrection(adminId, allocationId, cancel(line, 0, "勤務取消"))
                .andExpect(status().isCreated())).path("id").asText();
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 12), slot[1], slot[0], "4", "1300", 1,
                "時給を直して追加し直す")).andExpect(status().isCreated());
        assertAllocationAmounts(adminId, allocationId, "5200", "5200", "400");

        postReversal(adminId, allocationId, cancelId, 2, "原明細を戻す").andExpect(status().isConflict());
        assertAllocationAmounts(adminId, allocationId, "5200", "5200", "400");
    }

    @Test
    @DisplayName("試練36 ADD A→取消→同slot同人 ADD B→A の取消の取消は409、取消済み A の MODIFY も409")
    void 試練36_追加明細の復活が重複する() throws Exception {
        Long allocationId = allocation("50000");
        long[] slot = publishedSlot(teamId, LocalDate.of(2026, 6, 13), LocalTime.of(9, 0), LocalTime.of(13, 0), workerId);
        closeJune();

        String lineA = data(postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 13), slot[1],
                slot[0], "4", "1200", 0, "追加A")).andExpect(status().isCreated())).path("added_line_id").asText();
        String cancelA = data(postCorrection(adminId, allocationId, cancelAdded(lineA, 1, "A を取消"))
                .andExpect(status().isCreated())).path("id").asText();
        postCorrection(adminId, allocationId, add(workerId, LocalDate.of(2026, 6, 13), slot[1], slot[0], "4", "1300", 2,
                "追加B")).andExpect(status().isCreated());
        assertAllocationAmounts(adminId, allocationId, "5200", "5200", "5200");

        postReversal(adminId, allocationId, cancelA, 3, "A を戻す").andExpect(status().isConflict());
        postCorrection(adminId, allocationId, modifyAdded(lineA, "3", "1200", 3, "取消済み A の修正"))
                .andExpect(status().isConflict());
        assertAllocationAmounts(adminId, allocationId, "5200", "5200", "5200");
    }
}
