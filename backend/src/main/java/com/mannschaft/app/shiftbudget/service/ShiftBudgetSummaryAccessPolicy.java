package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 消化サマリ専用の認可。role・membershipを読むため業務TXの外から呼ぶ。 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetSummaryAccessPolicy {

    private final AccessControlService accessControlService;

    /** SYSも実所属・VIEWを必須とし、詳細を返せるADMINかを返す。 */
    boolean requireViewAndDetectAdmin(Long userId, Long organizationId) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")
                || !accessControlService.hasPermission(userId, organizationId, "ORGANIZATION", "BUDGET_VIEW")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
        return accessControlService.hasPermission(userId, organizationId, "ORGANIZATION", "BUDGET_ADMIN");
    }

    /** 集計後の表示判定。所属・VIEW・ADMINのいずれかが失効すれば閲覧者の形状に伏せる。 */
    boolean isBudgetAdmin(Long userId, Long organizationId) {
        return accessControlService.isMember(userId, organizationId, "ORGANIZATION")
                && accessControlService.hasPermission(userId, organizationId, "ORGANIZATION", "BUDGET_VIEW")
                && accessControlService.hasPermission(userId, organizationId, "ORGANIZATION", "BUDGET_ADMIN");
    }
}
