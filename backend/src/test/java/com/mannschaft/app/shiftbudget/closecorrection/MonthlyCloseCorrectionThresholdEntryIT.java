package com.mannschaft.app.shiftbudget.closecorrection;

import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 検分指摘4: 閾値の判定は、訂正の入口に限らず全入口（消化記録・取消の公開リスナー、失敗イベントの再試行）で
 * 訂正後の消化額を使う。これらの入口が呼ぶ {@code evaluateAndTrigger} が、未訂正のキャッシュだけで判定してはならない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 閾値判定の全入口は訂正後の消化額を使う（実DB）")
class MonthlyCloseCorrectionThresholdEntryIT extends MonthlyCloseCorrectionTestBase {

    @Autowired private ThresholdAlertEvaluationService thresholdAlertEvaluationService;

    @Test
    @DisplayName("試練46 減額訂正で訂正後58%の割当は、公開リスナー・再試行の入口（evaluateAndTrigger）でも警告を発火しない")
    void 試練46_公開入口も訂正後消化額で判定する() throws Exception {
        Long allocationId = allocation("10000");
        planned(allocationId, workerId, "1200", "4");
        planned(allocationId, workerId, "1300", "4");
        closeJune();
        Long lineA = consumptionIdOf(allocationId, "1200");
        // 4800 → 600（0.5h×1200）で当月は 5800 円（58%）。キャッシュは訂正されず 10000 円（100%）のまま
        postCorrection(adminId, allocationId, modify(lineA, "0.5", "1200", 0, "退勤打刻の誤り"))
                .andExpect(MockMvcResultMatchers.status().isCreated());
        assertThat(alertCount(allocationId)).as("訂正の入口は訂正後の消化額で判定済み").isZero();

        thresholdAlertEvaluationService.evaluateAndTrigger(allocationId);

        assertThat(alertCount(allocationId)).as("未訂正のキャッシュ（100%）で警告を発火してはならない").isZero();
    }

    private int alertCount(Long allocationId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM budget_threshold_alerts WHERE allocation_id = ?",
                Integer.class, allocationId);
        return n == null ? 0 : n;
    }
}
