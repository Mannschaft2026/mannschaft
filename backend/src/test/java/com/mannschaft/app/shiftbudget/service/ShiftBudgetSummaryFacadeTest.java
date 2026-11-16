package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionOverlay;
import com.mannschaft.app.budget.service.ShiftBudgetCorrectionQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.TodoBudgetLinkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 実Spring proxyとTX managerで、package-privateの計算入口までの順序・TX境界を固定する。
 * JDBC接続/Repositoryはmock。認可の真偽・SQLはactualfilters MySQL ITで別に検証する。
 */
@ExtendWith(MockitoExtension.class)
class ShiftBudgetSummaryFacadeTest {
    @Mock private ShiftBudgetFeatureService featureService;
    @Mock private AccessControlService accessControlService;
    @Mock private ShiftBudgetAllocationRepository allocationRepository;
    @Mock private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock private TodoBudgetLinkRepository todoBudgetLinkRepository;
    @Mock private BudgetThresholdAlertRepository alertRepository;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;

    private AnnotationConfigApplicationContext context;
    private ShiftBudgetSummaryFacade facade;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig { }

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("100", null, List.of()));
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.getAutoCommit()).thenReturn(true);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ShiftBudgetSummaryService.class, () -> new ShiftBudgetSummaryService(
                allocationRepository, consumptionRepository, todoBudgetLinkRepository, alertRepository,
                featureService, accessControlService));
        context.registerBean(ShiftBudgetSummaryAccessPolicy.class,
                () -> new ShiftBudgetSummaryAccessPolicy(accessControlService));
        context.registerBean(ShiftBudgetSummaryFacade.class, () -> new ShiftBudgetSummaryFacade(
                featureService, context.getBean(ShiftBudgetSummaryAccessPolicy.class),
                context.getBean(ShiftBudgetSummaryService.class),
                org.mockito.Mockito.mock(ShiftBudgetCorrectionQueryService.class, invocation -> ShiftBudgetCorrectionOverlay.EMPTY)));
        context.refresh();
        facade = context.getBean(ShiftBudgetSummaryFacade.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void featureと所属VIEW_ADMINはTX外で順に判定し計算だけreadOnlyTXに入る() {
        doAnswer(invocation -> {
            assertNoTransaction();
            return null;
        }).when(featureService).requireEnabled(1L);
        when(accessControlService.isMember(100L, 1L, "ORGANIZATION")).thenAnswer(invocation -> {
            assertNoTransaction();
            return true;
        });
        when(accessControlService.hasPermission(eq(100L), eq(1L), eq("ORGANIZATION"), any()))
                .thenAnswer(invocation -> {
                    assertNoTransaction();
                    return true;
                });
        givenAllocationInTransaction();

        assertThat(AopUtils.isCglibProxy(context.getBean(ShiftBudgetSummaryService.class))).isTrue();
        assertThat(facade.getConsumptionSummary(1L, 42L).allocationId()).isEqualTo(42L);
        assertNoTransaction();
        var order = inOrder(featureService, accessControlService, allocationRepository);
        order.verify(featureService).requireEnabled(1L);
        order.verify(accessControlService).isMember(100L, 1L, "ORGANIZATION");
        order.verify(accessControlService).hasPermission(100L, 1L, "ORGANIZATION", "BUDGET_VIEW");
        order.verify(accessControlService).hasPermission(100L, 1L, "ORGANIZATION", "BUDGET_ADMIN");
        order.verify(allocationRepository).findByIdAndOrganizationIdAndDeletedAtIsNull(42L, 1L);
    }

    @Test
    void feature無効なら認可と計算に進まない() {
        doThrow(new BusinessException(ShiftBudgetErrorCode.FEATURE_DISABLED))
                .when(featureService).requireEnabled(1L);
        assertThatThrownBy(() -> facade.getConsumptionSummary(1L, 42L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(accessControlService, allocationRepository, consumptionRepository, alertRepository);
    }

    @Test
    void 非所属ならVIEW_ADMIN判定と計算に進まない() {
        assertThatThrownBy(() -> facade.getConsumptionSummary(1L, 42L)).isInstanceOf(BusinessException.class);
        verify(accessControlService).isMember(100L, 1L, "ORGANIZATION");
        verify(accessControlService, never()).hasPermission(any(), any(), any(), any());
        verifyNoInteractions(allocationRepository, consumptionRepository, alertRepository);
    }

    @Test
    void 所属していてもVIEWなしならADMIN判定と計算に進まない() {
        when(accessControlService.isMember(100L, 1L, "ORGANIZATION")).thenReturn(true);
        assertThatThrownBy(() -> facade.getConsumptionSummary(1L, 42L)).isInstanceOf(BusinessException.class);
        verify(accessControlService, never()).hasPermission(100L, 1L, "ORGANIZATION", "BUDGET_ADMIN");
        verifyNoInteractions(allocationRepository, consumptionRepository, alertRepository);
    }

    @Test
    void 集計後VIEWが失効したらADMINが残っていても表示判定は降格する() {
        when(accessControlService.isMember(100L, 1L, "ORGANIZATION")).thenReturn(true);
        when(accessControlService.hasPermission(100L, 1L, "ORGANIZATION", "BUDGET_VIEW"))
                .thenReturn(true, false);
        when(accessControlService.hasPermission(100L, 1L, "ORGANIZATION", "BUDGET_ADMIN")).thenReturn(true);
        givenAllocationInTransaction();
        facade.getConsumptionSummary(1L, 42L);
        assertThat(facade.isBudgetAdmin(1L)).isFalse();
        // ADMINは初回だけ。再確認はVIEW拒否で止める。
        verify(accessControlService).hasPermission(100L, 1L, "ORGANIZATION", "BUDGET_ADMIN");
    }

    private void givenAllocationInTransaction() {
        when(allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(42L, 1L))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    return Optional.of(ShiftBudgetAllocationEntity.builder().allocatedAmount(BigDecimal.TEN)
                            .consumedAmount(BigDecimal.ZERO).confirmedAmount(BigDecimal.ZERO).build());
                });
    }

    private void assertNoTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}
