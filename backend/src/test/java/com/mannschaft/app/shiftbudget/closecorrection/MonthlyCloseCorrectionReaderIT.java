package com.mannschaft.app.shiftbudget.closecorrection;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 試練（reader と表示・性能）: 7・13・16。
 *
 * <p>訂正後確定額 = confirmed_amount + Σposted_delta、訂正後消化額 = max(0, consumed − confirmed) + 訂正後確定額。
 * 一覧・詳細・更新結果・summary・個人別・時給別・閾値が同じ訂正後値を返すことを実 HTTP で検査する。
 * 実装前は訂正 API が 500 を返すか、応答に {@code correction_delta_total} が無いため red になる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 月次締め後訂正 reader（実DB・実HTTP）")
class MonthlyCloseCorrectionReaderIT extends MonthlyCloseCorrectionTestBase {

    // ───────────── 試練07 reader 5種＋閾値の一致（AC4） ─────────────

    @Test
    @DisplayName("試練07 一覧・詳細・更新結果・summary・個人別・時給別・閾値が同じ訂正後値を返す")
    void 試練07_reader5種と閾値が訂正後値で一致する() throws Exception {
        Long worker2 = newUser();
        tx.executeWithoutResult(s -> MembershipTestHelper.insertMembership(em, worker2, ScopeType.ORGANIZATION, orgId,
                RoleKind.MEMBER));
        Long allocationId = allocation("10000");
        planned(allocationId, workerId, "1200", "4");
        Long lineB = planned(allocationId, worker2, "1350", "2");
        closeJune();

        postCorrection(adminId, allocationId, modify(lineB, "2", "1850", 0, "時給改定の反映漏れ"))
                .andExpect(status().isCreated());

        JsonNode listed = null;
        for (JsonNode item : data(list(adminId).andExpect(status().isOk())).path("items")) {
            if (item.path("id").asLong() == allocationId) {
                listed = item;
            }
        }
        assertThat(listed).as("一覧に割当が出る").isNotNull();
        assertNumber(listed, "consumed_amount", "8500");
        assertNumber(listed, "confirmed_amount", "8500");
        assertNumber(listed, "correction_delta_total", "1000");

        assertAllocationAmounts(adminId, allocationId, "8500", "8500", "1000");

        JsonNode updated = data(update(adminId, allocationId, "10000").andExpect(status().isOk()));
        assertNumber(updated, "consumed_amount", "8500");
        assertNumber(updated, "confirmed_amount", "8500");
        assertNumber(updated, "correction_delta_total", "1000");

        JsonNode s = data(summary(adminId, allocationId).andExpect(status().isOk()));
        assertNumber(s, "consumed_amount", "8500");
        assertNumber(s, "confirmed_amount", "8500");
        assertNumber(s, "planned_amount", "0");
        assertNumber(s, "remaining_amount", "1500");
        assertNumber(s, "correction_delta_total", "1000");
        JsonNode worker2Row = null;
        for (JsonNode u : s.path("by_user")) {
            if (u.path("user_id").asLong() == worker2) {
                worker2Row = u;
            }
        }
        assertThat(worker2Row).as("個人別に勤務者2が出る").isNotNull();
        assertNumber(worker2Row, "amount", "3700");
        assertNumber(worker2Row, "hours", "2");
        assertThat(worker2Row.path("rate_breakdown")).hasSize(1);
        assertNumber(worker2Row.path("rate_breakdown").get(0), "hourly_rate_snapshot", "1850");
        assertNumber(worker2Row.path("rate_breakdown").get(0), "amount", "3700");

        // 閾値: 75% → 85% へ訂正で越えた 80% を訂正後消化額で発火する（訂正イベントの後段は非同期でよい）
        Map<String, Object> alert = awaitAlert(allocationId, 80);
        assertThat(alert).as("訂正後消化額で 80%% 閾値の警告が出る").isNotNull();
        assertThat((BigDecimal) alert.get("consumed_amount_at_trigger")).isEqualByComparingTo("8500");
    }

    private Map<String, Object> awaitAlert(Long allocationId, int threshold) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT consumed_amount_at_trigger FROM "
                    + "budget_threshold_alerts WHERE allocation_id = ? AND threshold_percent = ?", allocationId, threshold);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(200);
        }
        return null;
    }

    // ───────────── 試練16 端数累積・全件取消・PLANNED 混在（AC4改） ─────────────

    @Test
    @DisplayName("試練16 端数累積（キャッシュ300円・原仕訳301円）を全件取消し PLANNED が混在しても 消化額≥確定額≥0 で各 reader 一致")
    void 試練16_端数累積と全件取消とPLANNED混在() throws Exception {
        Long allocationId = allocation("10000");
        Long l1 = planned(allocationId, workerId, "100.49", "1");
        Long l2 = planned(allocationId, workerId, "100.49", "1");
        Long l3 = planned(allocationId, workerId, "100.49", "1");
        closeJune();
        assertThat(allocationCache(allocationId).get("consumed_amount").toString()).isEqualTo("300");
        assertThat(allocationCache(allocationId).get("confirmed_amount").toString()).isEqualTo("301");
        planned(allocationId, workerId, "500", "1");

        postCorrection(adminId, allocationId, cancel(l1, 0, "取消1")).andExpect(status().isCreated());
        postCorrection(adminId, allocationId, cancel(l2, 1, "取消2")).andExpect(status().isCreated());
        postCorrection(adminId, allocationId, cancel(l3, 2, "取消3")).andExpect(status().isCreated());

        // max(0, 800 − 301) + 0 = 499（予定額部分の既存端数ずれは範囲外）
        assertAllocationAmounts(adminId, allocationId, "499", "0", "-301");
        JsonNode s = data(summary(adminId, allocationId).andExpect(status().isOk()));
        assertNumber(s, "consumed_amount", "499");
        assertNumber(s, "confirmed_amount", "0");
        assertNumber(s, "remaining_amount", "9501");
        for (JsonNode item : data(list(adminId).andExpect(status().isOk())).path("items")) {
            if (item.path("id").asLong() == allocationId) {
                assertNumber(item, "consumed_amount", "499");
                assertNumber(item, "confirmed_amount", "0");
            }
        }
    }

    @Test
    @DisplayName("試練16 訂正の無い割当でも端数で確定額が消化額を超えるなら 消化額=max(消化額, 確定額) を返す")
    void 試練16_訂正なしはmax() throws Exception {
        Long allocationId = allocation("10000");
        planned(allocationId, workerId, "100.49", "1");
        planned(allocationId, workerId, "100.49", "1");
        planned(allocationId, workerId, "100.49", "1");
        closeJune();

        assertAllocationAmounts(adminId, allocationId, "301", "301", "0");
        JsonNode s = data(summary(adminId, allocationId).andExpect(status().isOk()));
        assertNumber(s, "consumed_amount", "301");
        assertNumber(s, "confirmed_amount", "301");
    }

    // ───────────── 試練13 クエリ数（AC12） ─────────────

    @Test
    @DisplayName("試練13 履歴は1ページ最大100件で revision 順、100件と1000件でクエリ数が変わらない")
    void 試練13_履歴のクエリ数は件数に依らない() throws Exception {
        Long allocationId = allocation("100000000");
        Long line = planned(allocationId, workerId, "1000", "4");
        closeJune();
        seedCorrections(allocationId, line, 0, 100);

        history(adminId, allocationId).andExpect(status().isOk());
        int small;
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            JsonNode h = data(history(adminId, allocationId).andExpect(status().isOk()));
            assertThat(h.path("items")).hasSize(100);
            small = capture.totalCount();
        }
        seedCorrections(allocationId, line, 100, 1000);
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            JsonNode h = data(mvcHistoryPage(allocationId, 3, 500));
            assertThat(h.path("items")).as("1ページは最大100件").hasSize(100);
            assertThat(h.path("items").get(0).path("revision").asLong()).isEqualTo(301);
            assertThat(h.path("total").asLong()).isEqualTo(1000);
            assertThat(capture.totalCount()).isEqualTo(small);
        }
    }

    @Test
    @DisplayName("試練13 割当一覧の訂正合成は割当数に依らずクエリ数一定（N+1 なし）")
    void 試練13_一覧の訂正合成はクエリ数一定() throws Exception {
        Long first = allocation("100000");
        Long firstLine = planned(first, workerId, "1000", "4");
        closeJune();
        postCorrection(adminId, first, modify(firstLine, "3", "1000", 0, "訂正")).andExpect(status().isCreated());
        list(adminId).andExpect(status().isOk());
        int small;
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            list(adminId).andExpect(status().isOk());
            small = capture.totalCount();
        }
        for (int i = 0; i < 20; i++) {
            Long fy = tx.execute(st -> insertFiscalYear(orgId, adminId));
            Long cat = tx.execute(st -> insertCategory(fy));
            Long other = allocation(orgId, teamId, fy, cat, "100000");
            Long line = consumption(other, nextSlot(), workerId, "1000", "4",
                    com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus.CONFIRMED);
            jdbc.update("UPDATE shift_budget_allocations SET consumed_amount = 4000, confirmed_amount = 4000 WHERE id = ?",
                    other);
            jdbc.update("INSERT INTO budget_transactions (fiscal_year_id, category_id, scope_type, scope_id, "
                    + "transaction_type, amount, transaction_date, title, is_auto_recorded, source_type, source_id, "
                    + "approval_status, recorded_by, version, created_at, updated_at) VALUES (?, ?, 'TEAM', ?, 'EXPENSE', "
                    + "4000, '2026-06-30', '2026年6月 シフト人件費（自動集計）', 1, ?, ?, 'APPROVED', ?, 0, NOW(), NOW())",
                    fy, cat, teamId, SOURCE_MONTHLY, other, adminId);
            postCorrection(adminId, other, modify(line, "3", "1000", 0, "訂正")).andExpect(status().isCreated());
        }
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            JsonNode items = data(list(adminId).andExpect(status().isOk())).path("items");
            assertThat(items).hasSize(21);
            for (JsonNode item : items) {
                assertNumber(item, "correction_delta_total", "-1000");
            }
            assertThat(capture.totalCount()).isEqualTo(small);
        }
    }

    private void seedCorrections(Long allocationId, Long line, int fromRevision, int toRevision) throws Exception {
        for (int rev = fromRevision; rev < toRevision; rev++) {
            String hours = rev % 2 == 0 ? "3" : "4";
            postCorrection(adminId, allocationId, modify(line, hours, "1000", rev, "連続訂正" + rev))
                    .andExpect(status().isCreated());
        }
    }

    private org.springframework.test.web.servlet.ResultActions mvcHistoryPage(Long allocationId, int page, int size)
            throws Exception {
        return mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(base(allocationId, JUNE_KEY) + "/corrections")
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)), adminId, orgId))
                .andExpect(status().isOk());
    }
}
