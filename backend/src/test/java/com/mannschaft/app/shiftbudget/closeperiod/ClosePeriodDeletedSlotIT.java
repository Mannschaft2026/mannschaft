package com.mannschaft.app.shiftbudget.closeperiod;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetConsumptionReconcileBatchService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 α Codex 指摘 P2: 公開中スケジュールで勤務枠だけを削除すると、{@code ShiftSlotService.deleteSlot} は
 * {@code assigned_user_ids} を残したまま {@code shift_slots.deleted_at} を設定する（CMP-260923-0953 で導入した子テーブルの
 * 論理削除）。ネイティブ SQL には {@code @SQLRestriction} が効かないため、削除済みの勤務枠を明示的に除外しないと
 * 未計上候補・整合取消・締めの対象選定に「削除済みの勤務」が有効なものとして混ざる（実DB）。
 */
@DisplayName("CMP-260909-2152 α 削除済み勤務枠を未計上候補・整合取消・締め対象から除外する")
class ClosePeriodDeletedSlotIT extends ClosePeriodTestBase {

    @Autowired private ShiftSlotService slotService;
    @Autowired private ShiftBudgetConsumptionService consumptionService;
    @Autowired private ShiftBudgetConsumptionReconcileBatchService reconcileBatch;

    /** 既存スケジュールに勤務枠（9:00〜17:00）を1つ足す。戻り値は勤務枠 ID。 */
    private long addSlot(long scheduleId, LocalDate date, Long... userIds) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < userIds.length; i++) {
            json.append(i == 0 ? "" : ",").append(userIds[i]);
        }
        json.append(']');
        return tx.execute(s -> {
            ShiftSlotEntity slot = ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(date)
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(userIds.length)
                    .assignedUserIds(json.toString()).build();
            em.persist(slot);
            em.flush();
            return slot.getId();
        });
    }

    /** 本番と同じ経路（ShiftSlotService.deleteSlot）で勤務枠を論理削除する。担当者 JSON は残る。 */
    private void deleteSlot(long slotId) {
        slotService.deleteSlot(slotId);
        assertThat(jdbc.queryForObject("SELECT assigned_user_ids IS NOT NULL AND deleted_at IS NOT NULL FROM shift_slots WHERE id = ?",
                Boolean.class, slotId)).as("前提: 担当者 JSON を残したまま論理削除されている").isTrue();
    }

    @Test
    @DisplayName("チーム割当: 締め済み期間の未計上候補に、削除済み勤務枠の担当は出ない")
    void チーム割当の未計上候補に削除済み勤務枠が出ない() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        Long worker2 = worker(teamId, orgId);
        worked(d("2026-04-10"), workerId);
        // 公開済みだが記録していない勤務（未計上候補になる）
        long[] uncountedShift = shift(d("2026-04-12"), worker2);
        close("2026-04").andExpect(status().isOk());

        JsonNode before = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(before).as("前提: 削除前は未計上候補に出る").hasSize(1);

        deleteSlot(uncountedShift[1]);

        JsonNode after = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(after).as("削除済み勤務枠は未計上候補に出ない").isEmpty();
    }

    @Test
    @DisplayName("組織全体割当: 締め済み期間の未計上候補に、削除済み勤務枠の担当は出ない")
    void 組織全体割当の未計上候補に削除済み勤務枠が出ない() throws Exception {
        Long allocationId = allocation(orgId, null, fiscalYearId, categoryId, d("2026-04-01"), d("2026-05-31"));
        Long worker2 = worker(teamId, orgId);
        worked(d("2026-04-10"), workerId);
        long[] uncountedShift = shift(d("2026-04-12"), worker2);
        close("2026-04").andExpect(status().isOk());

        JsonNode before = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(before).as("前提: 削除前は未計上候補に出る").hasSize(1);

        deleteSlot(uncountedShift[1]);

        JsonNode after = data(uncounted(adminId, allocationId, "2026-04").andExpect(status().isOk())).path("items");
        assertThat(after).as("削除済み勤務枠は未計上候補に出ない").isEmpty();
    }

    @Test
    @DisplayName("整合取消（遅延再実行と同じ cancelAllForShift）: 公開中のまま削除された勤務枠の担当は有効と判定せず PLANNED を取消・減算する")
    void 整合取消は削除済み勤務枠の担当を有効と判定しない() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        Long worker2 = worker(teamId, orgId);
        long[] s = shift(d("2026-04-10"), workerId);
        long deletedSlot = addSlot(s[0], d("2026-04-11"), worker2);
        publish(s[0]);
        assertThat(liveStatus(deletedSlot, worker2)).isEqualTo("PLANNED");
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("16000");

        deleteSlot(deletedSlot);
        consumptionService.cancelAllForShift(s[0]);

        assertThat(liveStatus(deletedSlot, worker2)).as("削除済み勤務枠の PLANNED は取り消される").isNull();
        assertThat(liveStatus(s[1], workerId)).as("生きている勤務枠は残る").isEqualTo("PLANNED");
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("8000");
    }

    @Test
    @DisplayName("整合バッチ: 公開中スケジュールの削除済み勤務枠に残った PLANNED を孤児として拾い、取消・減算する")
    void 整合バッチは削除済み勤務枠のPLANNEDを取り消す() {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        Long worker2 = worker(teamId, orgId);
        long[] s = shift(d("2026-04-10"), workerId);
        long deletedSlot = addSlot(s[0], d("2026-04-11"), worker2);
        publish(s[0]);
        deleteSlot(deletedSlot);

        // 他テストの残骸で 1 回の上限（BATCH_SIZE）を超えても対象へ届くよう、収束するまで回す
        for (int i = 0; i < 20 && reconcileBatch.reconcileOrphanConsumptions() > 0; i++) {
            // 収束待ち
        }

        assertThat(liveStatus(deletedSlot, worker2)).as("削除済み勤務枠の PLANNED は整合バッチが取り消す").isNull();
        assertThat(liveStatus(s[1], workerId)).as("生きている勤務枠は残る").isEqualTo("PLANNED");
        assertThat(consumedAmount(allocationId)).isEqualByComparingTo("8000");
    }

    @Test
    @DisplayName("締め（Prepare の対象選定）: 削除済み勤務枠の PLANNED は確定せず、生きている勤務だけが CONFIRMED になる")
    void 締めは削除済み勤務枠のPLANNEDを確定しない() throws Exception {
        Long allocationId = allocation(d("2026-04-01"), d("2026-05-31"));
        Long worker2 = worker(teamId, orgId);
        long[] s = shift(d("2026-04-10"), workerId);
        long deletedSlot = addSlot(s[0], d("2026-04-11"), worker2);
        publish(s[0]);
        deleteSlot(deletedSlot);

        close("2026-04").andExpect(status().isOk());

        assertThat(liveStatus(s[1], workerId)).isEqualTo("CONFIRMED");
        assertThat(liveStatus(deletedSlot, worker2)).as("削除済み勤務枠の消化は締めで確定しない").isNotEqualTo("CONFIRMED");
        assertThat(confirmedAmount(allocationId)).isEqualByComparingTo("8000");
    }
}
