package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRecordDeferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftBudgetConsumptionService} 単体テスト（Phase 9-β / 設計書 §11.1）。
 *
 * <p>CMP-260909-2152 で記録・取消はロック順（割当 → 消化 → スケジュール行 FOR SHARE → 締めイベント）・遅延記録・
 * 締めイベントの印を伴う実DBの挙動になったため、記録・取消の判定表と並行は実DBの試験
 * （{@code closeperiod} パッケージの {@code ClosePeriodConcurrencyIT}・{@code ClosePeriodCloseIT}・
 * {@code ClosePeriodStageIT}）で検証する。本単体テストは、副作用の前に拒否する入口検証と勤務時間の計算だけを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetConsumptionService 単体テスト")
class ShiftBudgetConsumptionServiceTest {

    private static final Long ALLOCATION_ID = 42L;
    private static final Long SHIFT_ID = 5L;
    private static final Long SLOT_ID = 7L;
    private static final Long USER_ID = 100L;
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 11, 4);

    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetMonthlyCloseEventRepository eventRepository;
    @Mock
    private ShiftBudgetRecordDeferralRepository deferralRepository;
    @Mock
    private ShiftBudgetRateQueryRepository rateQueryRepository;
    @Mock
    private ShiftBudgetFailedEventService failedEventService;
    @Mock
    private ShiftBudgetLockStepHooks hooks;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ShiftBudgetConsumptionService service;

    @BeforeEach
    void setUp() {
        service = new ShiftBudgetConsumptionService(consumptionRepository, allocationRepository, eventRepository,
                deferralRepository, rateQueryRepository, failedEventService, hooks, new ObjectMapper(), transactionManager);
    }

    @Nested
    @DisplayName("recordSingleConsumption の入口検証")
    class RecordSingleConsumption {

        @org.junit.jupiter.params.ParameterizedTest(name = "rate={0} は IllegalArgumentException で拒否し、取消・INSERT・残高更新を一切しない")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "0.00", "-1", "-1200"})
        @DisplayName("時給が 0 以下は 0 円で記録せず拒否する (CMP-261115-1821)")
        void 時給0以下は副作用なしで拒否(String rate) {
            assertRejectedWithoutSideEffects(new BigDecimal(rate));
        }

        @Test
        @DisplayName("時給未設定 (rate=null) は 0 円で記録せず拒否する (CMP-261115-1821)")
        void 時給未設定は副作用なしで拒否() {
            assertRejectedWithoutSideEffects(null);
        }

        private void assertRejectedWithoutSideEffects(BigDecimal rate) {
            assertThatThrownBy(() -> service.recordSingleConsumption(
                    ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID, rate, new BigDecimal("4.0"), WORK_DATE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hourlyRate");

            // 発見の SELECT すら行わない = 既存 PLANNED の取消・減算が起きない
            verifyNoInteractions(consumptionRepository, deferralRepository, eventRepository);
            verify(allocationRepository, never()).incrementConsumedAmount(any(), any());
            verify(allocationRepository, never()).decrementConsumedAmount(any(), any());
        }
    }

    @Nested
    @DisplayName("calculateHours (深夜跨ぎ)")
    class CalculateHours {

        @Test
        @DisplayName("通常: 09:00-13:00 → 4.00h")
        void 通常時間() {
            BigDecimal h = ShiftBudgetConsumptionService.calculateHours(
                    LocalTime.of(9, 0), LocalTime.of(13, 0));
            assertThat(h).isEqualByComparingTo("4.00");
        }

        @Test
        @DisplayName("深夜跨ぎ: 22:00-03:00 → 5.00h (翌日扱い)")
        void 深夜跨ぎ() {
            BigDecimal h = ShiftBudgetConsumptionService.calculateHours(
                    LocalTime.of(22, 0), LocalTime.of(3, 0));
            assertThat(h).isEqualByComparingTo("5.00");
        }
    }
}
