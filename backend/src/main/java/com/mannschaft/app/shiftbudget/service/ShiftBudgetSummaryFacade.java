package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 消化サマリの段取り役。認可と他ドメインのfeature確認をTX外で済ませる。
 * 計算は別BeanのService proxyを通し、shiftbudgetだけのreadOnly TXで行う。
 */
@Service
@RequiredArgsConstructor
public class ShiftBudgetSummaryFacade {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetSummaryAccessPolicy policy;
    private final ShiftBudgetSummaryService summaryService;

    public ConsumptionSummaryResponse getConsumptionSummary(Long organizationId, Long allocationId) {
        featureService.requireEnabled(organizationId);
        boolean budgetAdmin = policy.requireViewAndDetectAdmin(SecurityUtils.getCurrentUserId(), organizationId);
        return summaryService.getConsumptionSummary(organizationId, allocationId, budgetAdmin);
    }

    /** Controllerの表示直前にも同じ実組織のPolicyで確認する（業務TX外）。 */
    public boolean isBudgetAdmin(Long organizationId) {
        return policy.isBudgetAdmin(SecurityUtils.getCurrentUserId(), organizationId);
    }
}
