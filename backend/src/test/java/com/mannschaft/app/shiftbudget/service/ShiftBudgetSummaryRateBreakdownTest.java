package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 集計だけの境界試験。認可・SQL・永続化の証拠は別の実MySQL ITが担う。 */
@ExtendWith(MockitoExtension.class)
class ShiftBudgetSummaryRateBreakdownTest {
    @Mock private ShiftBudgetAllocationRepository allocationRepository;
    @Mock private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock private TodoBudgetLinkRepository todoBudgetLinkRepository;
    @Mock private BudgetThresholdAlertRepository alertRepository;
    @Mock private ShiftBudgetFeatureService featureService;
    @Mock private AccessControlService accessControlService;
    @InjectMocks private ShiftBudgetSummaryService service;

    @BeforeEach
    void givenAllocation() {
        given(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(42L, 1L))
                .willReturn(Optional.of(ShiftBudgetAllocationEntity.builder()
                        .allocatedAmount(new BigDecimal("100000")).consumedAmount(new BigDecimal("6200"))
                        .confirmedAmount(BigDecimal.ZERO).build()));
    }

    @Test
    void 同額のscale違いをまとめ時給順に返して従来の合計を保持する() throws Exception {
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(42L,
                List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED)))
                .willReturn(List.of(row("1300", "2"), row("1200.00", "1"), row("1200.0", "2"), row("0", "8")));
        var response = service.getConsumptionSummary(1L, 42L, true);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode user = mapper.readTree(mapper.writerWithView(BudgetView.BudgetAdmin.class)
                .writeValueAsString(response)).path("by_user").get(0);
        assertThat(user.path("amount").decimalValue()).isEqualByComparingTo("6200");
        assertThat(user.path("hours").decimalValue()).isEqualByComparingTo("13");
        JsonNode rates = user.path("rate_breakdown");
        assertThat(rates.size()).isEqualTo(3);
        assertThat(rates.get(0).path("hourly_rate_snapshot").decimalValue()).isZero();
        assertThat(rates.get(1).path("hourly_rate_snapshot").decimalValue()).isEqualByComparingTo("1200");
        assertThat(rates.get(1).path("hours").decimalValue()).isEqualByComparingTo("3");
        assertThat(rates.get(1).path("amount").decimalValue()).isEqualByComparingTo("3600");
        assertThat(rates.get(2).path("hourly_rate_snapshot").decimalValue()).isEqualByComparingTo("1300");
        verify(consumptionRepository, times(1)).findByAllocationIdAndStatusInAndDeletedAtIsNull(42L,
                List.of(ShiftBudgetConsumptionStatus.PLANNED, ShiftBudgetConsumptionStatus.CONFIRMED));
    }

    private ShiftBudgetConsumptionEntity row(String rate, String hours) {
        BigDecimal r = new BigDecimal(rate);
        BigDecimal h = new BigDecimal(hours);
        return ShiftBudgetConsumptionEntity.builder().userId(6L).hourlyRateSnapshot(r)
                .hours(h).amount(r.multiply(h)).build();
    }
}
