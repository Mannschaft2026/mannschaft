package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MonthlyShiftBudgetCloseGate} 単体テスト（CMP-261021-0657）。
 *
 * <p>月末を過ぎていない月（当月・未来月）の締めを拒否し、副作用（割当取得・仕訳 INSERT・確定額加算・
 * 組織走査・失敗イベント記録）を一切起こさないことを、実体の {@link MonthlyShiftBudgetCloseService}
 * （依存は mock）と組み合わせて確かめる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyShiftBudgetCloseGate 単体テスト")
class MonthlyShiftBudgetCloseGateTest {

    private static final Long ORG_ID = 1L;
    private static final Long ALLOCATION_ID = 42L;
    private static final Long USER_ID = 100L;
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    /** 基準の壁時計: 2026-11-24 12:00 JST（当月=2026-11）。 */
    private static final Clock BASE_CLOCK = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), JST);

    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private BudgetTransactionRepository budgetTransactionRepository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private ShiftBudgetFailedEventService failedEventService;
    @Mock
    private ObjectProvider<MonthlyShiftBudgetCloseService> selfProvider;

    private MonthlyShiftBudgetCloseService closeService;
    private MonthlyShiftBudgetCloseGate gate;

    @BeforeEach
    void setUp() {
        closeService = new MonthlyShiftBudgetCloseService(
                allocationRepository, consumptionRepository, budgetTransactionRepository,
                featureService, accessControlService, auditLogService,
                organizationRepository, failedEventService, selfProvider);
        lenient().when(selfProvider.getObject()).thenReturn(closeService);
        lenient().when(allocationRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> Optional.of(sampleAllocation()));
        useClock(BASE_CLOCK);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void useClock(Clock clock) {
        gate = new MonthlyShiftBudgetCloseGate(closeService, featureService, clock);
    }

    private ShiftBudgetAllocationEntity sampleAllocation() {
        try {
            ShiftBudgetAllocationEntity alloc = ShiftBudgetAllocationEntity.builder()
                    .organizationId(ORG_ID).teamId(12L)
                    .fiscalYearId(3L).budgetCategoryId(17L)
                    .periodStart(LocalDate.of(2026, 10, 1))
                    .periodEnd(LocalDate.of(2026, 10, 31))
                    .allocatedAmount(new BigDecimal("300000"))
                    .consumedAmount(new BigDecimal("100000"))
                    .confirmedAmount(BigDecimal.ZERO)
                    .currency("JPY").createdBy(1L).version(0L)
                    .build();
            java.lang.reflect.Field idField =
                    alloc.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(alloc, ALLOCATION_ID);
            return alloc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ShiftBudgetConsumptionEntity samplePlannedConsumption(BigDecimal amount) {
        return ShiftBudgetConsumptionEntity.builder()
                .allocationId(ALLOCATION_ID)
                .shiftId(5L).slotId(1L).userId(USER_ID)
                .hourlyRateSnapshot(new BigDecimal("1200"))
                .hours(new BigDecimal("4.00"))
                .amount(amount)
                .currency("JPY")
                .status(ShiftBudgetConsumptionStatus.PLANNED)
                .recordedAt(LocalDateTime.of(2026, 10, 15, 9, 0))
                .build();
    }

    private OrganizationEntity orgEntity(Long id) {
        try {
            OrganizationEntity org = OrganizationEntity.builder().build();
            java.lang.reflect.Field idField =
                    org.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(org, id);
            return org;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void givenBudgetAdmin() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
    }

    private void givenOneClosableAllocation() {
        given(allocationRepository.findLiveByOrgAndPeriodRange(any(), any(), any()))
                .willReturn(List.of(sampleAllocation()));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                any(), any(), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(any(), any()))
                .willReturn(List.of(samplePlannedConsumption(new BigDecimal("4800"))));
    }

    private void assertMonthNotEnded(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_032");
                    assertThat(be.getFieldErrors())
                            .extracting(ErrorResponse.FieldError::getField)
                            .containsExactly("year_month");
                });
    }

    private void assertNoCloseSideEffects() {
        verify(allocationRepository, never()).findLiveByOrgAndPeriodRange(any(), any(), any());
        verify(budgetTransactionRepository, never()).save(any());
        verify(allocationRepository, never()).incrementConfirmedAmount(anyLong(), any());
        verify(consumptionRepository, never()).save(any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未来月 (2026-12) の締めは MONTH_NOT_ENDED (032) で拒否し、割当取得も仕訳も行わない")
    void 未来月は拒否() {
        givenBudgetAdmin();

        assertMonthNotEnded(() -> gate.close(ORG_ID, YearMonth.of(2026, 12)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("当月 (2026-11) の締めは MONTH_NOT_ENDED (032) で拒否し、割当取得も仕訳も行わない")
    void 当月は拒否() {
        givenBudgetAdmin();

        assertMonthNotEnded(() -> gate.close(ORG_ID, YearMonth.of(2026, 11)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("前月 (2026-10) は従来どおり締められる")
    void 前月は成功() {
        givenBudgetAdmin();
        givenOneClosableAllocation();

        MonthlyShiftBudgetCloseService.CloseResult result =
                gate.close(ORG_ID, YearMonth.of(2026, 10));

        assertThat(result.closedAllocations()).isEqualTo(1);
        verify(budgetTransactionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("JST 境界: 2026-11-30 23:59:59.999 JST では 2026-11 はまだ締められない")
    void JST境界_月末最終瞬間は拒否() {
        useClock(Clock.fixed(Instant.parse("2026-11-30T14:59:59.999Z"), JST));
        givenBudgetAdmin();

        assertMonthNotEnded(() -> gate.close(ORG_ID, YearMonth.of(2026, 11)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("JST 境界: 2026-12-01 00:00:00 JST になれば 2026-11 を締められる（UTC ではまだ 11/30）")
    void JST境界_翌月初は成功() {
        useClock(Clock.fixed(Instant.parse("2026-11-30T15:00:00Z"), JST));
        givenBudgetAdmin();
        givenOneClosableAllocation();

        MonthlyShiftBudgetCloseService.CloseResult result =
                gate.close(ORG_ID, YearMonth.of(2026, 11));

        assertThat(result.closedAllocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("closeAll(当月) はループ前に 1 回だけ拒否し、組織走査も失敗イベント記録もしない")
    void closeAll_当月は1回だけ拒否() {
        assertMonthNotEnded(() -> gate.closeAll(YearMonth.of(2026, 11)));

        verify(organizationRepository, never()).findAll();
        verify(failedEventService, never()).recordFailure(any(), any(), any(), any(), any());
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("closeAll(前月) は従来どおり全組織を締める")
    void closeAll_前月は成功() {
        given(organizationRepository.findAll()).willReturn(List.of(orgEntity(ORG_ID)));
        given(featureService.isEnabled(ORG_ID)).willReturn(true);
        givenOneClosableAllocation();

        MonthlyShiftBudgetCloseService.AllOrgsCloseResult result =
                gate.closeAll(YearMonth.of(2026, 10));

        assertThat(result.processedOrganizationIds()).containsExactly(ORG_ID);
        verify(failedEventService, never()).recordFailure(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("権限なし＋未来月は権限エラー (027/403) が先に出る（月の判定を権限外に見せない）")
    void 権限なし未来月は権限エラーが先() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

        assertThatThrownBy(() -> gate.close(ORG_ID, YearMonth.of(2026, 12)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED.getCode()));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("機能フラグ OFF＋未来月は機能フラグのエラーが最初に出る（権限・対象月より先）")
    void 機能フラグOFFが最優先() {
        BusinessException disabled = new BusinessException(
                com.mannschaft.app.common.CommonErrorCode.COMMON_002);
        org.mockito.BDDMockito.willThrow(disabled).given(featureService).requireEnabled(ORG_ID);

        assertThatThrownBy(() -> gate.close(ORG_ID, YearMonth.of(2026, 12))).isSameAs(disabled);
        verify(accessControlService, never()).isSystemAdmin(any());
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("cron の対象月: 2026-12-01 02:00 JST（UTC 11/30 17:00）の前月は 2026-11")
    void previousMonthはJST基準() {
        useClock(Clock.fixed(Instant.parse("2026-11-30T17:00:00Z"), JST));

        assertThat(gate.previousMonth()).isEqualTo(YearMonth.of(2026, 11));
    }
}
