package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionOverlay;
import com.mannschaft.app.budget.service.ShiftBudgetCorrectionQueryService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 消化サマリの段取り役。認可と他ドメインのfeature確認をTX外で済ませる。
 * 計算は別BeanのService proxyを通し、shiftbudgetだけのreadOnly TXで行う。
 *
 * <p>CMP-260909-2152: 月次締め後の訂正は budget の読取 TX で先に値として取得し、計算 TX へ値で渡す
 * （計算 TX から budget の Repository へは触れない）。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftBudgetSummaryFacade {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetSummaryAccessPolicy policy;
    private final ShiftBudgetSummaryService summaryService;
    private final ShiftBudgetCorrectionQueryService correctionQueryService;

    public ConsumptionSummaryResponse getConsumptionSummary(Long organizationId, Long allocationId) {
        featureService.requireEnabled(organizationId);
        boolean budgetAdmin = policy.requireViewAndDetectAdmin(SecurityUtils.getCurrentUserId(), organizationId);
        ShiftBudgetCorrectionOverlay overlay = correctionQueryService.overlay(allocationId);
        return summaryService.getConsumptionSummary(organizationId, allocationId, budgetAdmin, overlay);
    }

    /** Controllerの表示直前にも同じ実組織のPolicyで確認する（業務TX外）。 */
    public boolean isBudgetAdmin(Long organizationId) {
        return policy.isBudgetAdmin(SecurityUtils.getCurrentUserId(), organizationId);
    }
}
