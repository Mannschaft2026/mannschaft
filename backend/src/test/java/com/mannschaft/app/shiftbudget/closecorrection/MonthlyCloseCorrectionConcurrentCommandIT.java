package com.mannschaft.app.shiftbudget.closecorrection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 AC10（検分指摘1）: 同じ command_id の同時送信は両方とも同じ結果（履歴1・仕訳1）になる。
 *
 * <p>年度行を別接続で握って 2 リクエストを同時に待たせ、両者が最初の command 検索（スナップショット確定）を
 * 済ませた状態から解放する。後発が年度行ロック待ちの後の再検索で、先発のコミット済み行を読めなければ
 * revision 不一致で 409 になる（REPEATABLE READ の落とし穴）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 同 command 同時送信（実DB・実HTTP）")
class MonthlyCloseCorrectionConcurrentCommandIT extends MonthlyCloseCorrectionTestBase {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("試練47 AC10 同じ command_id を同時に2回送っても両方 201 で、履歴1件・差額仕訳1件")
    void 試練47_同command同時送信は両方同結果() throws Exception {
        Long allocationId = allocation("50000");
        planned(allocationId, workerId, "1200", "4");
        planned(allocationId, workerId, "1300", "4");
        closeJune();
        Long lineA = consumptionIdOf(allocationId, "1200");
        Map<String, Object> body = modify(lineA, "1.5", "1200", 0, "同時送信");

        CompletableFuture<Integer> first;
        CompletableFuture<Integer> second;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT id FROM budget_fiscal_years WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, fiscalYearId);
                ps.executeQuery().close();
            }
            first = CompletableFuture.supplyAsync(() -> send(allocationId, body));
            second = CompletableFuture.supplyAsync(() -> send(allocationId, body));
            Thread.sleep(2000);
            assertThat(first.isDone() || second.isDone()).as("両者とも年度行ロックを待つはず").isFalse();
            holder.commit();
        }
        assertThat(first.get(60, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(second.get(60, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_monthly_close_corrections WHERE command_id = UNHEX(REPLACE(?, '-', ''))",
                Integer.class, (String) body.get("command_id")))
                .isEqualTo(1);
        assertThat(journals(allocationId, SOURCE_CORRECTION)).hasSize(1);
    }

    private int send(Long allocationId, Map<String, Object> body) {
        try {
            return postCorrection(adminId, allocationId, body).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
