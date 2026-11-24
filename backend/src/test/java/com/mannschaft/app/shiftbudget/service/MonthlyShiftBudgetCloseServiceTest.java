package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MonthlyShiftBudgetCloseService} 単体テスト（Phase 9-δ 第2段、API #11 / cron バッチ）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>正常系: 生存 PLANNED → CONFIRMED 遷移 + budget_transactions INSERT</li>
 *   <li>重複実行検知: 既に締め済 → MONTHLY_ALREADY_CLOSED (409)</li>
 *   <li>cron バッチ経由: フィーチャーフラグ OFF 組織 → スキップ</li>
 *   <li>権限なし (BUDGET_ADMIN なし) → 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonthlyShiftBudgetCloseService 単体テスト")
class MonthlyShiftBudgetCloseServiceTest {

    private static final Long ORG_ID = 1L;
    private static final Long ALLOCATION_ID = 42L;
    private static final Long USER_ID = 100L;
    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 6);
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
    /**
     * 自己プロキシのプロバイダ（CMP-260910-1556）。
     *
     * <p>本番では Spring が AOP プロキシを返し、{@code closeOneAllocation} の
     * {@code REQUIRES_NEW} が実際に効く。ユニットテストではプロキシが存在しないため
     * 被テストインスタンス自身を返す。<b>この差異ゆえに自己呼び出しの欠陥は
     * ユニットテストでは検出できない</b>（実 DB での検証は
     * {@code MonthlyShiftBudgetCloseTransactionIT} が担う）。</p>
     */
    @Mock
    private ObjectProvider<MonthlyShiftBudgetCloseService> selfProvider;

    private MonthlyShiftBudgetCloseService service;

    @BeforeEach
    void setUp() {
        useClock(BASE_CLOCK);
        // CMP-260910-1556 検分 P1-2: closeOneAllocation は冒頭で allocation 行を排他ロックする。
        // mock のままだと empty が返り「割当が消滅した」扱いで全件 skip される（= 全検体が静かに 0 件になる）。
        lenient().when(allocationRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> Optional.of(sampleAllocation()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    /** 壁時計を差し替えて被テストインスタンスを作り直す（自己プロキシも新インスタンスへ向ける）。 */
    private void useClock(Clock clock) {
        service = new MonthlyShiftBudgetCloseService(
                allocationRepository, consumptionRepository, budgetTransactionRepository,
                featureService, accessControlService, auditLogService,
                organizationRepository, failedEventService, selfProvider, clock);
        lenient().when(selfProvider.getObject()).thenReturn(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ShiftBudgetAllocationEntity sampleAllocation() {
        try {
            ShiftBudgetAllocationEntity alloc = ShiftBudgetAllocationEntity.builder()
                    .organizationId(ORG_ID).teamId(12L)
                    .fiscalYearId(3L).budgetCategoryId(17L)
                    .periodStart(LocalDate.of(2026, 6, 1))
                    .periodEnd(LocalDate.of(2026, 6, 30))
                    .allocatedAmount(new BigDecimal("300000"))
                    .consumedAmount(new BigDecimal("100000"))
                    .confirmedAmount(BigDecimal.ZERO)
                    .currency("JPY").createdBy(1L).version(0L)
                    .build();
            // テストでは reflection で id を注入
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
                .recordedAt(java.time.LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("正常系: PLANNED 2件 → CONFIRMED 遷移 + budget_transactions 1件 INSERT")
    void 正常系_PLANNED_to_CONFIRMED() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                eq(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY),
                eq(ALLOCATION_ID), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                eq(ALLOCATION_ID), eq(List.of(ShiftBudgetConsumptionStatus.PLANNED))))
                .willReturn(List.of(
                        samplePlannedConsumption(new BigDecimal("4800")),
                        samplePlannedConsumption(new BigDecimal("5200"))));

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(1);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(0);
        assertThat(result.closedConsumptions()).isEqualTo(2);

        // confirmed_amount を 4800+5200=10000 加算
        verify(allocationRepository, times(1)).incrementConfirmedAmount(ALLOCATION_ID, new BigDecimal("10000"));

        // budget_transactions に 1 件 INSERT
        ArgumentCaptor<BudgetTransactionEntity> txCaptor =
                ArgumentCaptor.forClass(BudgetTransactionEntity.class);
        verify(budgetTransactionRepository, times(1)).save(txCaptor.capture());
        BudgetTransactionEntity tx = txCaptor.getValue();
        assertThat(tx.getSourceType()).isEqualTo(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY);
        assertThat(tx.getSourceId()).isEqualTo(ALLOCATION_ID);
        assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(tx.getTitle()).contains("2026年6月");
        assertThat(tx.getIsAutoRecorded()).isTrue();

        // 監査ログ
        verify(auditLogService).record(eq("SHIFT_BUDGET_MONTHLY_CLOSED"),
                eq(USER_ID), any(), any(), eq(ORG_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重複実行: 既に締め済 → ALREADY_CLOSED 検出 + 件数 0 / alreadyClosed 1")
    void 重複実行_ALREADY_CLOSED検出() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                eq(MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY),
                eq(ALLOCATION_ID), any())).willReturn(true);

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(0);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(1);
        assertThat(result.closedConsumptions()).isEqualTo(0);

        // INSERT も update も発生しない
        verify(budgetTransactionRepository, never()).save(any());
        verify(allocationRepository, never()).incrementConfirmedAmount(anyLong(), any());
    }

    @Test
    @DisplayName("cron バッチ経由: フィーチャーフラグ OFF 組織 → スキップ (CloseResult 0,0,0)")
    void cron_フラグOFF組織はスキップ() {
        given(featureService.isEnabled(ORG_ID)).willReturn(false);

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.closeFromBatch(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(0);
        assertThat(result.alreadyClosedAllocations()).isEqualTo(0);
        assertThat(result.closedConsumptions()).isEqualTo(0);
        // 何の DB アクセスも発生しない
        verify(allocationRepository, never()).findLiveByOrgAndPeriodRange(any(), any(), any());
    }

    @Test
    @DisplayName("権限なし (BUDGET_ADMIN なし) → BUDGET_ADMIN_REQUIRED (403)")
    void 権限なし() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        org.mockito.BDDMockito.willThrow(
                new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.close(ORG_ID, TARGET_MONTH))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("PLANNED 0件: allocation はあるが消化なし → tx INSERT は発生するが amount=0")
    void PLANNED0件_amount0でtx生成() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                any(), any(), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                eq(ALLOCATION_ID), any())).willReturn(List.of());

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, TARGET_MONTH);

        assertThat(result.closedAllocations()).isEqualTo(1);
        assertThat(result.closedConsumptions()).isEqualTo(0);

        // amount 0 なので incrementConfirmedAmount は呼ばれない（負数禁止 CHECK 制約への配慮）
        verify(allocationRepository, never()).incrementConfirmedAmount(anyLong(), any());
        // tx は 1 件作成（amount=0、冪等性のための「締め済印」）
        verify(budgetTransactionRepository, times(1)).save(any());
    }

    // ====================================================================
    // Phase 10-β: closeAll の部分失敗リカバリ
    // ====================================================================

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

    @Test
    @DisplayName("closeAll: 全組織成功 → processedOrganizationIds に全件含む")
    void closeAll_全組織成功() {
        OrganizationEntity org1 = orgEntity(1L);
        OrganizationEntity org2 = orgEntity(2L);
        given(organizationRepository.findAll()).willReturn(List.of(org1, org2));
        // 両組織ともフィーチャーフラグ ON で 1 件ずつ締めが成功
        given(featureService.isEnabled(1L)).willReturn(true);
        given(featureService.isEnabled(2L)).willReturn(true);
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        given(allocationRepository.findLiveByOrgAndPeriodRange(any(), any(), any()))
                .willReturn(List.of(alloc));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                any(), any(), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(any(), any()))
                .willReturn(List.of(samplePlannedConsumption(new BigDecimal("4800"))));

        MonthlyShiftBudgetCloseService.AllOrgsCloseResult result =
                service.closeAll(TARGET_MONTH);

        assertThat(result.processedOrganizationIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(result.failedOrganizationIds()).isEmpty();
        assertThat(result.alreadyClosedOrganizationIds()).isEmpty();
        assertThat(result.closedAllocations()).isEqualTo(2);
        verify(failedEventService, never()).recordFailure(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("closeAll: 1 組織が例外で失敗 → failedOrganizationIds + failedEvent 記録 + 他組織は続行")
    void closeAll_1組織失敗で他組織は続行() {
        OrganizationEntity ok = orgEntity(1L);
        OrganizationEntity ng = orgEntity(2L);
        given(organizationRepository.findAll()).willReturn(List.of(ok, ng));
        given(featureService.isEnabled(1L)).willReturn(true);
        given(featureService.isEnabled(2L)).willReturn(true);
        // 組織1: 成功
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(1L), any(), any()))
                .willReturn(List.of(sampleAllocation()));
        // 組織2: findLive で例外
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(2L), any(), any()))
                .willThrow(new RuntimeException("DB lock timeout"));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                any(), any(), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(any(), any()))
                .willReturn(List.of(samplePlannedConsumption(new BigDecimal("4800"))));

        MonthlyShiftBudgetCloseService.AllOrgsCloseResult result =
                service.closeAll(TARGET_MONTH);

        assertThat(result.processedOrganizationIds()).containsExactly(1L);
        assertThat(result.failedOrganizationIds()).containsExactly(2L);
        // failed_events に記録されたか
        verify(failedEventService).recordFailure(eq(2L), any(), any(), any(), any());
    }

    @Test
    @DisplayName("closeAll: フィーチャーフラグ OFF 組織は processed/failed どちらにも含めない (CloseResult 0,0,0 → 無視)")
    void closeAll_フラグOFF組織はリスト未掲載() {
        OrganizationEntity offOrg = orgEntity(3L);
        given(organizationRepository.findAll()).willReturn(List.of(offOrg));
        given(featureService.isEnabled(3L)).willReturn(false);

        MonthlyShiftBudgetCloseService.AllOrgsCloseResult result =
                service.closeAll(TARGET_MONTH);

        assertThat(result.processedOrganizationIds()).isEmpty();
        assertThat(result.failedOrganizationIds()).isEmpty();
        assertThat(result.alreadyClosedOrganizationIds()).isEmpty();
        verify(failedEventService, never()).recordFailure(any(), any(), any(), any(), any());
    }

    // ═══════════════════════════════════════════════════════════════
    // CMP-261021-0657: 月末を過ぎていない月（当月・未来月）は締められない
    // ═══════════════════════════════════════════════════════════════

    private void givenBudgetAdmin() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
    }

    private void givenOneClosableAllocation() {
        given(allocationRepository.findLiveByOrgAndPeriodRange(eq(ORG_ID), any(), any()))
                .willReturn(List.of(sampleAllocation()));
        given(budgetTransactionRepository.existsBySourceTypeAndSourceIdAndTransactionDate(
                any(), any(), any())).willReturn(false);
        given(consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(any(), any()))
                .willReturn(List.of(samplePlannedConsumption(new BigDecimal("4800"))));
    }

    private void assertMonthNotEnded(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode().getCode()).isEqualTo("SHIFT_BUDGET_032");
                    assertThat(be.getFieldErrors())
                            .extracting(com.mannschaft.app.common.ErrorResponse.FieldError::getField)
                            .containsExactly("year_month");
                });
    }

    private void assertNoCloseSideEffects() {
        verify(allocationRepository, never()).findLiveByOrgAndPeriodRange(any(), any(), any());
        verify(budgetTransactionRepository, never()).save(any());
        verify(allocationRepository, never()).incrementConfirmedAmount(anyLong(), any());
    }

    @Test
    @DisplayName("CMP-261021-0657 未来月 (2026-12) の締めは MONTH_NOT_ENDED (032) で拒否し、割当取得も仕訳も行わない")
    void 未来月は拒否() {
        givenBudgetAdmin();

        assertMonthNotEnded(() -> service.close(ORG_ID, YearMonth.of(2026, 12)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("CMP-261021-0657 当月 (2026-11) の締めは MONTH_NOT_ENDED (032) で拒否し、割当取得も仕訳も行わない")
    void 当月は拒否() {
        givenBudgetAdmin();

        assertMonthNotEnded(() -> service.close(ORG_ID, YearMonth.of(2026, 11)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("CMP-261021-0657 前月 (2026-10) は従来どおり締められる")
    void 前月は成功() {
        givenBudgetAdmin();
        givenOneClosableAllocation();

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, YearMonth.of(2026, 10));

        assertThat(result.closedAllocations()).isEqualTo(1);
        verify(budgetTransactionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("CMP-261021-0657 JST 境界: 2026-11-30 23:59:59.999 JST では 2026-11 はまだ締められない")
    void JST境界_月末最終秒は拒否() {
        useClock(Clock.fixed(Instant.parse("2026-11-30T14:59:59.999Z"), JST));
        givenBudgetAdmin();

        assertMonthNotEnded(() -> service.close(ORG_ID, YearMonth.of(2026, 11)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("CMP-261021-0657 JST 境界: 2026-12-01 00:00 JST になれば 2026-11 を締められる（UTC ではまだ 11/30）")
    void JST境界_翌月初は成功() {
        useClock(Clock.fixed(Instant.parse("2026-11-30T15:00:00Z"), JST));
        givenBudgetAdmin();
        givenOneClosableAllocation();

        MonthlyShiftBudgetCloseService.CloseResult result =
                service.close(ORG_ID, YearMonth.of(2026, 11));

        assertThat(result.closedAllocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("CMP-261021-0657 closeAll(当月) はループ前に 1 回だけ拒否し、組織走査も失敗イベント記録もしない")
    void closeAll_当月は1回だけ拒否() {
        assertMonthNotEnded(() -> service.closeAll(YearMonth.of(2026, 11)));

        verify(organizationRepository, never()).findAll();
        verify(failedEventService, never()).recordFailure(any(), any(), any(), any(), any());
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("CMP-261021-0657 closeFromBatch(当月) も共通経路の多重防御で 032 拒否し、割当取得も仕訳も行わない")
    void closeFromBatch_当月は共通経路で拒否() {
        given(featureService.isEnabled(ORG_ID)).willReturn(true);

        assertMonthNotEnded(() -> service.closeFromBatch(ORG_ID, YearMonth.of(2026, 11)));
        assertNoCloseSideEffects();
    }

    @Test
    @DisplayName("CMP-261021-0657 権限なし＋未来月は権限エラー (027/403) が先に出る（月の情報を権限外に漏らさない）")
    void 権限なし未来月は権限エラーが先() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

        assertThatThrownBy(() -> service.close(ORG_ID, YearMonth.of(2026, 12)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED.getCode()));
        assertNoCloseSideEffects();
    }
}
