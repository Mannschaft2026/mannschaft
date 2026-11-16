package com.mannschaft.app.shiftbudget.closecorrection;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 試練39・40（AC21 ワンクリック追加の BE）。
 *
 * <p>締め済み月で「割当はあるが CONFIRMED も生存 ADD も無い勤務」を {@code GET .../uncounted-shifts} が返し、
 * 各行に slot・人・勤務日・時間・その勤務日に有効な時給（未設定は null と理由）を初期値として載せる。
 * 候補から ADD すると一覧から消え、履歴に締め後追加として出る。実装前は 500 で red。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 締め後に未計上の勤務（実DB・実HTTP）")
class MonthlyCloseUncountedShiftIT extends MonthlyCloseCorrectionTestBase {

    private Long allocationId;
    private Long worker2;
    private Long noRateWorker;
    private long[] counted;       // 締めで CONFIRMED（除外）
    private long[] skipped;       // 消化なし（候補・時給1200・8時間）
    private long[] lateRate;      // 6/25 勤務（候補・6/20 改定後の時給1500・3時間）
    private long[] addedAlready;  // 既に ADD 済み（除外）
    private long[] noRate;        // 時給未設定（候補・ボタン無効）
    private long[] shared;        // 2人割当で worker は CONFIRMED、worker2 は未計上（worker2 のみ候補）
    private long[] otherTeam;     // 他割当の勤務（除外）
    private long[] july;          // 対象月外（除外）

    @BeforeEach
    void seed() throws Exception {
        worker2 = newUser();
        noRateWorker = newUser();
        tx.executeWithoutResult(s -> {
            for (Long u : new Long[] {worker2, noRateWorker}) {
                MembershipTestHelper.insertMembership(em, u, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
                MembershipTestHelper.insertMembership(em, u, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            }
        });
        hourlyRate(workerId, teamId, "1200", LocalDate.of(2026, 1, 1));
        hourlyRate(workerId, teamId, "1500", LocalDate.of(2026, 6, 20));
        hourlyRate(worker2, teamId, "1300", LocalDate.of(2026, 1, 1));

        allocationId = allocation("500000");
        counted = publishedSlot(teamId, LocalDate.of(2026, 6, 5), LocalTime.of(9, 0), LocalTime.of(13, 0), workerId);
        skipped = publishedSlot(teamId, LocalDate.of(2026, 6, 6), LocalTime.of(9, 0), LocalTime.of(17, 0), workerId);
        lateRate = publishedSlot(teamId, LocalDate.of(2026, 6, 25), LocalTime.of(9, 0), LocalTime.of(12, 0), workerId);
        addedAlready = publishedSlot(teamId, LocalDate.of(2026, 6, 7), LocalTime.of(9, 0), LocalTime.of(13, 0), worker2);
        noRate = publishedSlot(teamId, LocalDate.of(2026, 6, 8), LocalTime.of(9, 0), LocalTime.of(13, 0), noRateWorker);
        shared = publishedSlot(teamId, LocalDate.of(2026, 6, 9), LocalTime.of(10, 0), LocalTime.of(12, 0),
                workerId, worker2);
        Long secondTeam = tx.execute(s -> {
            Long t = insertTeam();
            insertTeamOrgMembership(t, orgId);
            return t;
        });
        Long secondFy = tx.execute(s -> insertFiscalYear(orgId, adminId));
        allocation(orgId, secondTeam, secondFy, tx.execute(s -> insertCategory(secondFy)), "500000");
        otherTeam = publishedSlot(secondTeam, LocalDate.of(2026, 6, 6), LocalTime.of(9, 0), LocalTime.of(17, 0), workerId);
        july = publishedSlot(teamId, LocalDate.of(2026, 7, 1), LocalTime.of(9, 0), LocalTime.of(17, 0), workerId);

        consumption(allocationId, counted[1], workerId, "1200", "4", ShiftBudgetConsumptionStatus.PLANNED);
        consumption(allocationId, shared[1], workerId, "1200", "2", ShiftBudgetConsumptionStatus.PLANNED);
        closeJune();
    }

    /** 既に締め後追加済みの勤務を作る（各試験の冒頭で呼ぶ。実装前はここで 500 → red）。 */
    private void addAlready() throws Exception {
        postCorrection(adminId, allocationId, add(worker2, LocalDate.of(2026, 6, 7), addedAlready[1], addedAlready[0],
                "4", "1300", 0, "既に追加済み")).andExpect(status().isCreated());
    }

    private Map<String, JsonNode> candidates() throws Exception {
        JsonNode items = data(uncounted(adminId, allocationId).andExpect(status().isOk())).path("items");
        assertThat(items.isArray()).as("data.items が配列").isTrue();
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode item : items) {
            byKey.put(item.path("slot_id").asLong() + ":" + item.path("user_id").asLong(), item);
        }
        return byKey;
    }

    private static String key(long[] slot, Long userId) {
        return slot[1] + ":" + userId;
    }

    // ───────────── 試練39 候補一覧の算出 ─────────────

    @Test
    @DisplayName("試練39 締め後に未計上の勤務だけを、その勤務日に有効な時給と時間を初期値に返す（計上済み・追加済み・他割当・月外は除外）")
    void 試練39_候補一覧の算出() throws Exception {
        addAlready();
        Map<String, JsonNode> c = candidates();

        assertThat(c.keySet()).containsExactlyInAnyOrder(
                key(skipped, workerId), key(lateRate, workerId), key(noRate, noRateWorker), key(shared, worker2));

        JsonNode s = c.get(key(skipped, workerId));
        assertThat(s.path("shift_id").asLong()).isEqualTo(skipped[0]);
        assertThat(s.path("work_date").asText()).isEqualTo("2026-06-06");
        assertNumber(s, "hours", "8");
        assertNumber(s, "hourly_rate", "1200");
        assertThat(s.path("add_available").asBoolean()).isTrue();

        JsonNode late = c.get(key(lateRate, workerId));
        assertNumber(late, "hours", "3");
        assertNumber(late, "hourly_rate", "1500");

        JsonNode sh = c.get(key(shared, worker2));
        assertNumber(sh, "hours", "2");
        assertNumber(sh, "hourly_rate", "1300");

        JsonNode nr = c.get(key(noRate, noRateWorker));
        assertThat(nr.path("hourly_rate").isNull()).as("時給未設定は null").isTrue();
        assertThat(nr.path("add_available").asBoolean()).as("時給未設定はボタン無効").isFalse();
        assertThat(nr.path("unavailable_reason").asText()).isNotBlank();
    }

    // ───────────── 試練40 候補からの ADD（BE） ─────────────

    @Test
    @DisplayName("試練40 候補の初期値で ADD すると一覧から消え、履歴に締め後追加（ADDED・slot 付き）として出る")
    void 試練40_候補からのADDで一覧から消え履歴に出る() throws Exception {
        addAlready();
        JsonNode candidate = candidates().get(key(skipped, workerId));
        assertThat(candidate).isNotNull();

        postCorrection(adminId, allocationId, add(workerId, LocalDate.parse(candidate.path("work_date").asText()),
                candidate.path("slot_id").asLong(), candidate.path("shift_id").asLong(),
                candidate.path("hours").decimalValue().toPlainString(),
                candidate.path("hourly_rate").decimalValue().toPlainString(), 1, "再公開でスキップされた勤務の追加"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("ADD"));

        assertThat(candidates()).doesNotContainKey(key(skipped, workerId));
        JsonNode items = data(history(adminId, allocationId).andExpect(status().isOk())).path("items");
        JsonNode last = items.get(items.size() - 1);
        assertThat(last.path("kind").asText()).isEqualTo("ADD");
        assertThat(last.path("line_kind").asText()).isEqualTo("ADDED");
        assertThat(last.path("slot_id").asLong()).isEqualTo(skipped[1]);
        assertNumber(last, "after_amount", "9600");
    }
}
