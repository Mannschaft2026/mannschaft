package com.mannschaft.app.shiftbudget.closecorrection;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 試練（訂正の中核）: 1〜4・6・8・9・11・12・18・19・21。
 *
 * <p>実 MySQL・実 MockMvc・実 Security で、締め後訂正の追記・取消・原本保護・途中失敗・年度クローズ・
 * 丸め・0件締め・締め操作者を検査する。実装前は訂正 API が未実装（500）のため、各試験は
 * 最初の HTTP ステータス、または未実装の応答欄・原本保護の欠如で意図どおり red になる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 月次締め後訂正 中核（実DB・実HTTP）")
class MonthlyCloseCorrectionCoreIT extends MonthlyCloseCorrectionTestBase {

    @Autowired private DataSource dataSource;

    /** 10000円の締め月（A=4h×1200=4800、B=4h×1300=5200）を作り、割当IDを返す。 */
    private Long closedTenThousand() {
        Long allocationId = allocation("50000");
        planned(allocationId, workerId, "1200", "4");
        planned(allocationId, workerId, "1300", "4");
        closeJune();
        assertThat(journals(allocationId, SOURCE_MONTHLY)).hasSize(1);
        assertThat((BigDecimal) journals(allocationId, SOURCE_MONTHLY).get(0).get("amount"))
                .isEqualByComparingTo("10000");
        return allocationId;
    }

    // ───────────── 試練01 実DB HTTP IT（AC1・AC2・AC7・AC8） ─────────────

    @Test
    @DisplayName("試練01 AC1 −3000円の訂正で当月7000円・原仕訳と元消化は不変・差額仕訳は元月末日付")
    void 試練01_減額訂正で訂正後額7000円になり原本は不変() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        List<Map<String, Object>> consumptionsBefore = consumptionRows(allocationId);
        List<Map<String, Object>> originalBefore = journals(allocationId, SOURCE_MONTHLY);

        ResultActions r = postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "退勤打刻の誤り"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("MODIFY"))
                .andExpect(jsonPath("$.data.line_kind").value("ORIGINAL"))
                .andExpect(jsonPath("$.data.revision").value(1));
        JsonNode c = data(r);
        assertNumber(c, "before_amount", "4800");
        assertNumber(c, "after_amount", "1800");
        assertNumber(c, "posted_delta", "-3000");

        assertAllocationAmounts(adminId, allocationId, "7000", "7000", "-3000");
        assertThat(consumptionRows(allocationId)).as("元消化行は書き換えない").isEqualTo(consumptionsBefore);
        assertThat(journals(allocationId, SOURCE_MONTHLY)).as("原月次仕訳は不変").isEqualTo(originalBefore);
        List<Map<String, Object>> deltas = journals(allocationId, SOURCE_CORRECTION);
        assertThat(deltas).hasSize(1);
        assertThat((BigDecimal) deltas.get(0).get("amount")).isEqualByComparingTo("-3000");
        assertThat(deltas.get(0).get("transaction_date").toString()).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("試練01 AC2 続けて+500円で7500円・履歴は revision 順に前後・差分・内容・理由・操作者・日時を返す")
    void 試練01_増額訂正を重ね履歴がrevision順に読める() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        Long lineB = consumptionIdOf(allocationId, "1300");
        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "退勤打刻の誤り"))
                .andExpect(status().isCreated());
        postCorrection(adminId, allocationId, modify(lineB, "4", "1425", 1, "時給改定の反映漏れ"))
                .andExpect(status().isCreated());

        assertAllocationAmounts(adminId, allocationId, "7500", "7500", "-2500");
        JsonNode h = data(history(adminId, allocationId).andExpect(status().isOk()));
        assertThat(h.path("items")).hasSize(2);
        JsonNode first = h.path("items").get(0);
        JsonNode second = h.path("items").get(1);
        assertThat(first.path("revision").asLong()).isEqualTo(1);
        assertThat(second.path("revision").asLong()).isEqualTo(2);
        assertNumber(second, "before_hourly_rate", "1300");
        assertNumber(second, "after_hourly_rate", "1425");
        assertNumber(second, "before_amount", "5200");
        assertNumber(second, "after_amount", "5700");
        assertNumber(second, "posted_delta", "500");
        assertThat(second.path("reason").asText()).isEqualTo("時給改定の反映漏れ");
        assertThat(second.path("content").asText()).isNotBlank();
        assertThat(second.path("actor_user_id").asLong()).isEqualTo(adminId);
        assertThat(second.path("created_at").isTextual()).isTrue();
    }

    @Test
    @DisplayName("試練01 AC7/AC8 理由 null・空白・501文字は400、500文字ちょうどは成功。負の時間は400。いずれも変更0")
    void 試練01_理由と値の境界() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        int before = budgetTransactionCount();

        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, null)).andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "   ")).andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "あ".repeat(501)))
                .andExpect(status().isBadRequest());
        postCorrection(adminId, allocationId, modify(lineA, "-1", "1200", 0, "負の時間"))
                .andExpect(status().isBadRequest());
        assertThat(budgetTransactionCount()).isEqualTo(before);

        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "あ".repeat(500)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("試練01 AC10 expected_revision が現在と異なれば409で金額不変")
    void 試練01_expected_revision不一致は409() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "一回目")).andExpect(status().isCreated());
        postCorrection(adminId, allocationId, modify(lineA, "1", "1200", 0, "古い版での二回目"))
                .andExpect(status().isConflict());
        assertAllocationAmounts(adminId, allocationId, "7000", "7000", "-3000");
    }

    // ───────────── 試練02 汎用 reverse 対照 ─────────────

    @Test
    @DisplayName("試練02 訂正仕訳は同年度・同費目・同scope・元月末日付の符号付きEXPENSEで、汎用取消（当日付の逆種別）の形にしない")
    void 試練02_訂正仕訳は符号付きEXPENSEで費目集計に反映される() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        Map<String, Object> original = journals(allocationId, SOURCE_MONTHLY).get(0);

        postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "退勤打刻の誤り"))
                .andExpect(status().isCreated());

        List<Map<String, Object>> deltas = journals(allocationId, SOURCE_CORRECTION);
        assertThat(deltas).hasSize(1);
        Map<String, Object> delta = deltas.get(0);
        assertThat(delta.get("transaction_type")).as("汎用取消は逆種別（INCOME）を作るので誤り").isEqualTo("EXPENSE");
        assertThat(delta.get("reversal_of_id")).as("汎用取消の連結を使わない").isNull();
        assertThat(delta.get("transaction_date").toString()).as("当日付ではなく元月末日").isEqualTo("2026-06-30");
        assertThat(delta.get("fiscal_year_id")).isEqualTo(original.get("fiscal_year_id"));
        assertThat(delta.get("category_id")).isEqualTo(original.get("category_id"));
        assertThat(delta.get("scope_type")).isEqualTo(original.get("scope_type"));
        assertThat(delta.get("scope_id")).isEqualTo(original.get("scope_id"));
        BigDecimal categoryExpense = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM budget_transactions "
                        + "WHERE category_id = ? AND transaction_type = 'EXPENSE' AND deleted_at IS NULL",
                BigDecimal.class, categoryId);
        assertThat(categoryExpense).as("費目の支出集計が訂正後額になる").isEqualByComparingTo("7000");
    }

    // ───────────── 試練03 CANCELLED 併存 ─────────────

    @Test
    @DisplayName("試練03 同slot同人の CANCELLED 行が併存しても締め済み明細を取消でき、消化行の状態は書き換えない")
    void 試練03_CANCELLED併存でも取消できる() throws Exception {
        Long allocationId = allocation("50000");
        long slot = nextSlot();
        consumption(allocationId, slot, workerId, "1200", "4", ShiftBudgetConsumptionStatus.CANCELLED);
        Long lineA = consumption(allocationId, slot, workerId, "1200", "4", ShiftBudgetConsumptionStatus.PLANNED);
        planned(allocationId, workerId, "1300", "4");
        closeJune();
        List<Map<String, Object>> rowsBefore = consumptionRows(allocationId);

        ResultActions r = postCorrection(adminId, allocationId, cancel(lineA, 0, "勤務取りやめの反映漏れ"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("CANCEL"))
                .andExpect(jsonPath("$.data.after_line_status").value("CANCELLED"));
        assertNumber(data(r), "after_amount", "0");

        assertAllocationAmounts(adminId, allocationId, "5200", "5200", "-4800");
        assertThat(consumptionRows(allocationId)).as("消化表の UNIQUE と衝突する状態変更をしない").isEqualTo(rowsBefore);
    }

    // ───────────── 試練04 取消連鎖（AC3） ─────────────

    @Test
    @DisplayName("試練04 取消は明細の最新訂正のみ・二重取消409・取消の取消可・別締めの訂正IDは404")
    void 試練04_取消連鎖() throws Exception {
        Long allocationId = allocation("50000");
        planned(allocationId, workerId, "1200", "4");
        planned(allocationId, workerId, "1300", "4");
        Long otherAllocation = allocation("50000");
        planned(otherAllocation, workerId, "1100", "2");
        closeJune();
        Long lineA = consumptionIdOf(allocationId, "1200");

        String c1 = data(postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "一回目"))
                .andExpect(status().isCreated())).path("id").asText();
        String c2 = data(postCorrection(adminId, allocationId, modify(lineA, "2.5", "1200", 1, "二回目"))
                .andExpect(status().isCreated())).path("id").asText();
        assertAllocationAmounts(adminId, allocationId, "8200", "8200", "-1800");

        postReversal(adminId, allocationId, c1, 2, "最新でない訂正の取消").andExpect(status().isConflict());
        assertAllocationAmounts(adminId, allocationId, "8200", "8200", "-1800");

        JsonNode r1 = data(postReversal(adminId, allocationId, c2, 2, "二回目は誤り")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("REVERSAL"))
                .andExpect(jsonPath("$.data.reverses_correction_id").value(c2)));
        assertNumber(r1, "before_amount", "3000");
        assertNumber(r1, "after_amount", "1800");
        assertNumber(r1, "posted_delta", "-1200");
        assertAllocationAmounts(adminId, allocationId, "7000", "7000", "-3000");

        postReversal(adminId, allocationId, c2, 3, "二重取消").andExpect(status().isConflict());
        postReversal(adminId, allocationId, r1.path("id").asText(), 3, "取消の取消").andExpect(status().isCreated());
        assertAllocationAmounts(adminId, allocationId, "8200", "8200", "-1800");

        Long otherLine = consumptionIdOf(otherAllocation, "1100");
        String foreign = data(postCorrection(adminId, otherAllocation, modify(otherLine, "1", "1100", 0, "別締め"))
                .andExpect(status().isCreated())).path("id").asText();
        postReversal(adminId, allocationId, foreign, 4, "別締めの訂正ID").andExpect(status().isNotFound());
        assertAllocationAmounts(adminId, allocationId, "8200", "8200", "-1800");
    }

    // ───────────── 試練06 途中失敗ロールバック（AC9） ─────────────

    @Test
    @DisplayName("試練06 訂正仕訳の INSERT が失敗すれば訂正行・締め記録の更新は残らず、原因除去後に同じ command で成功する")
    void 試練06_途中失敗は全ロールバック() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        Map<String, Object> body = modify(lineA, "1.5", "1200", 0, "退勤打刻の誤り");
        doThrow(new IllegalStateException("CMP-260909-2152: 訂正仕訳 INSERT 失敗を模す"))
                .when(budgetTransactionRepository).save(argThat((BudgetTransactionEntity e) ->
                        e != null && SOURCE_CORRECTION.equals(e.getSourceType())));
        doThrow(new IllegalStateException("CMP-260909-2152: 訂正仕訳 INSERT 失敗を模す"))
                .when(budgetTransactionRepository).saveAndFlush(argThat((BudgetTransactionEntity e) ->
                        e != null && SOURCE_CORRECTION.equals(e.getSourceType())));

        postCorrection(adminId, allocationId, body).andExpect(status().is5xxServerError());

        org.mockito.Mockito.reset(budgetTransactionRepository);
        assertThat(journals(allocationId, SOURCE_CORRECTION)).isEmpty();
        JsonNode h = data(history(adminId, allocationId).andExpect(status().isOk()));
        assertThat(h.path("items").isArray()).isTrue();
        assertThat(h.path("items")).as("訂正行が仕訳なしで残ってはならない").isEmpty();
        assertAllocationAmounts(adminId, allocationId, "10000", "10000", "0");

        postCorrection(adminId, allocationId, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revision").value(1));
        assertThat(journals(allocationId, SOURCE_CORRECTION)).hasSize(1);
    }

    // ───────────── 試練08 汎用削除・取消の拒否（AC5、HTTP 部分） ─────────────

    @Test
    @DisplayName("試練08 汎用の取引削除・取消は、訂正前の原仕訳・訂正後の原仕訳・訂正仕訳のいずれも409で金額不変")
    void 試練08_汎用削除取消は409() throws Exception {
        Long allocationId = closedTenThousand();
        Long teamAdmin = teamBudgetAdmin();
        Long control = tx.execute(s -> {
            BudgetTransactionEntity t = BudgetTransactionEntity.builder().fiscalYearId(fiscalYearId)
                    .categoryId(categoryId).scopeType("TEAM").scopeId(teamId)
                    .transactionType(BudgetTransactionType.EXPENSE).amount(new BigDecimal("100"))
                    .transactionDate(JUNE_END).title("2152 対照の手入力取引")
                    .approvalStatus(BudgetApprovalStatus.APPROVED).recordedBy(adminId).build();
            em.persist(t);
            em.flush();
            return t.getId();
        });
        // 対照: 同じ利用者・同じ scope の通常取引は従来どおり削除できる（権限の前提が成立している）
        genericDelete(teamAdmin, control).andExpect(status().isNoContent());

        Long original = originalJournalId(allocationId);
        genericDelete(teamAdmin, original).andExpect(status().isConflict());
        genericReverse(teamAdmin, original).andExpect(status().isConflict());

        postCorrection(adminId, allocationId, modify(consumptionIdOf(allocationId, "1200"), "1.5", "1200", 0, "訂正"))
                .andExpect(status().isCreated());
        Long delta = ((Number) journals(allocationId, SOURCE_CORRECTION).get(0).get("id")).longValue();
        genericDelete(teamAdmin, original).andExpect(status().isConflict());
        genericReverse(teamAdmin, original).andExpect(status().isConflict());
        genericDelete(teamAdmin, delta).andExpect(status().isConflict());
        genericReverse(teamAdmin, delta).andExpect(status().isConflict());

        assertThat((BigDecimal) journals(allocationId, SOURCE_MONTHLY).get(0).get("amount")).isEqualByComparingTo("10000");
        assertThat((BigDecimal) journals(allocationId, SOURCE_CORRECTION).get(0).get("amount"))
                .isEqualByComparingTo("-3000");
        Integer reversals = jdbc.queryForObject("SELECT COUNT(*) FROM budget_transactions WHERE reversal_of_id IN (?, ?)",
                Integer.class, original, delta);
        assertThat(reversals).isZero();
    }

    private ResultActions genericDelete(Long actorId, Long transactionId) throws Exception {
        return mvc.perform(as(delete("/api/v1/budget/transactions/" + transactionId)
                .param("scopeId", teamId.toString()).param("scopeType", "TEAM"), actorId, orgId));
    }

    private ResultActions genericReverse(Long actorId, Long transactionId) throws Exception {
        return mvc.perform(as(post("/api/v1/budget/transactions/" + transactionId + "/reverse"), actorId, orgId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"2152 汎用取消の試行\"}"));
    }

    // ───────────── 試練09 CLOSED 年度（AC6） ─────────────

    @Test
    @DisplayName("試練09 CLOSED 年度の締め月への訂正は BUDGET_004（409）で行増加0")
    void 試練09_CLOSED年度は拒否() throws Exception {
        Long allocationId = closedTenThousand();
        jdbc.update("UPDATE budget_fiscal_years SET status = 'CLOSED' WHERE id = ?", fiscalYearId);
        int before = budgetTransactionCount();

        postCorrection(adminId, allocationId, modify(consumptionIdOf(allocationId, "1200"), "1.5", "1200", 0, "訂正"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUDGET_004"));

        assertThat(budgetTransactionCount()).isEqualTo(before);
        JsonNode h = data(history(adminId, allocationId).andExpect(status().isOk()));
        assertThat(h.path("items").isArray()).isTrue();
        assertThat(h.path("items")).isEmpty();
    }

    // ───────────── 試練11 丸め境界（AC8） ─────────────

    @Test
    @DisplayName("試練11 100.49円×3件（原仕訳301円）で .49/.50 境界を跨ぐ訂正と戻しの差額が累積しない。差額0は仕訳なし")
    void 試練11_丸め境界() throws Exception {
        Long allocationId = allocation("50000");
        Long line1 = planned(allocationId, workerId, "100.49", "1");
        planned(allocationId, workerId, "100.49", "1");
        planned(allocationId, workerId, "100.49", "1");
        closeJune();
        assertThat((BigDecimal) journals(allocationId, SOURCE_MONTHLY).get(0).get("amount")).isEqualByComparingTo("301");

        JsonNode c1 = data(postCorrection(adminId, allocationId, modify(line1, "1", "100.51", 0, "301.49 → 301"))
                .andExpect(status().isCreated()));
        assertNumber(c1, "exact_delta", "0.02");
        assertNumber(c1, "posted_delta", "0");
        assertThat(c1.path("budget_transaction_id").isNull()).as("差額0は仕訳を作らない").isTrue();
        assertThat(journals(allocationId, SOURCE_CORRECTION)).isEmpty();

        JsonNode c2 = data(postCorrection(adminId, allocationId, modify(line1, "1", "100.52", 1, "301.50 → 302"))
                .andExpect(status().isCreated()));
        assertNumber(c2, "posted_delta", "1");

        JsonNode c3 = data(postCorrection(adminId, allocationId, modify(line1, "1", "100.49", 2, "301.47 → 301"))
                .andExpect(status().isCreated()));
        assertNumber(c3, "posted_delta", "-1");

        List<Map<String, Object>> deltas = journals(allocationId, SOURCE_CORRECTION);
        assertThat(deltas).hasSize(2);
        BigDecimal sum = deltas.stream().map(m -> (BigDecimal) m.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).as("訂正の重ね相殺で丸め誤差を累積させない").isEqualByComparingTo("0");
        assertAllocationAmounts(adminId, allocationId, "301", "301", "0");
    }

    // ───────────── 試練12 0件締め（AC7） ─────────────

    @Test
    @DisplayName("試練12 0件締め月の履歴は空で安定し、存在しない明細の訂正と未締め月の訂正は404")
    void 試練12_0件締めと未締め月() throws Exception {
        Long allocationId = allocation("50000");
        closeJune();
        assertThat((BigDecimal) journals(allocationId, SOURCE_MONTHLY).get(0).get("amount")).isEqualByComparingTo("0");

        for (int i = 0; i < 2; i++) {
            history(adminId, allocationId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(jsonPath("$.data.total").value(0));
        }
        postCorrection(adminId, allocationId, modify(999_999_999L, "1", "1000", 0, "存在しない明細"))
                .andExpect(status().isNotFound());
        postCorrection(adminId, orgId, allocationId, "2026-05", modify(999_999_999L, "1", "1000", 0, "未締め月"))
                .andExpect(status().isNotFound());
        assertThat(journals(allocationId, SOURCE_CORRECTION)).isEmpty();
    }

    // ───────────── 試練18 年度クローズとの同時実行（AC6追） ─────────────

    @Test
    @DisplayName("試練18 年度クローズが年度行を握っている間、訂正は年度行ロックを待ち、クローズ確定後は BUDGET_004 で行増加0")
    void 試練18_年度クローズと訂正の同時実行() throws Exception {
        Long allocationId = closedTenThousand();
        Long lineA = consumptionIdOf(allocationId, "1200");
        int before = budgetTransactionCount();

        try (Connection closing = dataSource.getConnection()) {
            closing.setAutoCommit(false);
            try (PreparedStatement ps = closing.prepareStatement(
                    "UPDATE budget_fiscal_years SET status = 'CLOSED' WHERE id = ?")) {
                ps.setLong(1, fiscalYearId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            CompletableFuture<Integer> correction = CompletableFuture.supplyAsync(() -> {
                try {
                    return postCorrection(adminId, allocationId, modify(lineA, "1.5", "1200", 0, "同時訂正"))
                            .andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(1500);
            assertThat(correction.isDone())
                    .as("訂正は年度行を FOR UPDATE で読み、未コミットのクローズを待たねばならない")
                    .isFalse();
            closing.commit();
            assertThat(correction.get(60, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(budgetTransactionCount()).isEqualTo(before);
        assertThat(journals(allocationId, SOURCE_CORRECTION)).isEmpty();
    }

    // ───────────── 試練19 月合計100億円以上（AC8追） ─────────────

    @Test
    @DisplayName("試練19 各明細は上限内で月合計100億円の締め月でも初回訂正が成功する")
    void 試練19_月合計100億円以上でも訂正できる() throws Exception {
        Long allocationId = allocation("30000000000");
        Long line1 = planned(allocationId, workerId, "50000000", "100");
        planned(allocationId, workerId, "50000001", "100");
        closeJune();
        assertThat((BigDecimal) journals(allocationId, SOURCE_MONTHLY).get(0).get("amount"))
                .isEqualByComparingTo("10000000100");

        JsonNode c = data(postCorrection(adminId, allocationId, modify(line1, "99", "50000000", 0, "1時間減"))
                .andExpect(status().isCreated()));
        assertNumber(c, "posted_delta", "-50000000");
        assertAllocationAmounts(adminId, allocationId, "9950000100", "9950000100", "-50000000");
    }

    // ───────────── 試練21 締め操作者（AC14） ─────────────

    @Test
    @DisplayName("試練21 締め操作者は 手動=MANUAL+ID、バッチ=BATCH+NULL、締めイベント以前=UNKNOWN+NULL で割当作成者を出さない")
    void 試練21_締め操作者の区分() throws Exception {
        Long closer = newUser();
        grantBudget(closer, orgId, true);

        Long manual = allocation("50000");
        planned(manual, workerId, "1200", "4");
        asUser(closer, () -> closeService.close(orgId, JUNE));

        Long batch = allocation("50000");
        planned(batch, workerId, "1200", "4");
        closeJuneFromBatch();

        Long legacy = allocation("50000");
        Long legacyLine = consumption(legacy, nextSlot(), workerId, "1200", "4", ShiftBudgetConsumptionStatus.CONFIRMED);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE shift_budget_allocations SET consumed_amount = 4800, confirmed_amount = 4800 "
                    + "WHERE id = :id").setParameter("id", legacy).executeUpdate();
            em.persist(BudgetTransactionEntity.builder().fiscalYearId(fiscalYearId).categoryId(categoryId)
                    .scopeType("TEAM").scopeId(teamId).transactionType(BudgetTransactionType.EXPENSE)
                    .amount(new BigDecimal("4800")).transactionDate(JUNE_END).title("2026年6月 シフト人件費（自動集計）")
                    .isAutoRecorded(true).sourceType(SOURCE_MONTHLY).sourceId(legacy)
                    .approvalStatus(BudgetApprovalStatus.APPROVED).recordedBy(adminId).build());
        });

        postCorrection(adminId, manual, modify(consumptionIdOf(manual, "1200"), "3", "1200", 0, "訂正"))
                .andExpect(status().isCreated());
        postCorrection(adminId, batch, modify(consumptionIdOf(batch, "1200"), "3", "1200", 0, "訂正"))
                .andExpect(status().isCreated());
        postCorrection(adminId, legacy, modify(legacyLine, "3", "1200", 0, "訂正"))
                .andExpect(status().isCreated());

        assertCloseActor(manual, "MANUAL", closer);
        assertCloseActor(batch, "BATCH", null);
        assertCloseActor(legacy, "UNKNOWN", null);
    }

    private void assertCloseActor(Long allocationId, String kind, Long actorId) throws Exception {
        JsonNode close = data(history(adminId, allocationId).andExpect(status().isOk())).path("close");
        assertThat(close.path("close_actor_kind").asText()).as("締め操作者区分").isEqualTo(kind);
        if (actorId == null) {
            assertThat(close.path("close_actor_user_id").isNull())
                    .as("割当作成者や仕訳記録者を締め操作者として提示しない: %s", close)
                    .isTrue();
        } else {
            assertThat(close.path("close_actor_user_id").asLong()).isEqualTo(actorId);
        }
        assertThat(close.path("closed_at").isTextual()).isTrue();
        assertNumber(close, "original_amount", "4800");
    }
}
