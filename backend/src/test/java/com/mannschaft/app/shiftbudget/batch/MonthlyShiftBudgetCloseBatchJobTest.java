package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MonthlyShiftBudgetCloseBatchJob} 単体テスト（CMP-261021-0657）。
 *
 * <p>cron は毎月 1 日 02:00 JST に起動する。対象月は壁時計（JST）基準の「前月」でなければならない。
 * JVM 既定ゾーンに依存して {@code YearMonth.now()} を使うと、UTC 環境では 1 日 02:00 JST が
 * まだ前日（前月末）であり、対象月が 1 か月ずれる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyShiftBudgetCloseBatchJob 単体テスト")
class MonthlyShiftBudgetCloseBatchJobTest {

    @Mock
    private MonthlyShiftBudgetCloseService closeService;

    @Test
    @DisplayName("CMP-261021-0657 2026-12-01 02:00 JST の起動で前月 2026-11 を closeAll する")
    void cron起動時は壁時計基準の前月を締める() {
        // 2026-12-01 02:00 JST = 2026-11-30 17:00 UTC
        Clock wallClock = Clock.fixed(Instant.parse("2026-11-30T17:00:00Z"), ZoneId.of("Asia/Tokyo"));
        MonthlyShiftBudgetCloseBatchJob job = new MonthlyShiftBudgetCloseBatchJob(closeService, wallClock);
        given(closeService.closeAll(any())).willReturn(
                new MonthlyShiftBudgetCloseService.AllOrgsCloseResult(
                        List.of(), List.of(), List.of(), 0, 0, 0));

        job.run();

        verify(closeService).closeAll(YearMonth.of(2026, 11));
    }
}
