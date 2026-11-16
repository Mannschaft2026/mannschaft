package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CMP-260909-2152 月次締め後訂正 API の認可（段取り役が業務 TX の外から呼ぶ。{@code @Transactional} を付けない）。
 *
 * <p>組織の実所属と BUDGET_VIEW / BUDGET_ADMIN で判定する。SYSTEM_ADMIN 単独（組織の実所属・権限なし）は拒否する。
 * 呼出元は割当を {@code (割当ID, 組織ID)} で引くため、他組織の割当は認可通過後に 404 になる。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetScopeAccessPolicy {

    private final AccessControlService accessControlService;

    /** 登録系（訂正・追加・取消・未計上の勤務）: 組織の BUDGET_ADMIN。 */
    public void requireBudgetAdmin(Long userId, Long organizationId) {
        if (!isMemberWith(userId, organizationId, "BUDGET_VIEW")
                || !accessControlService.hasPermission(userId, organizationId, "ORGANIZATION", "BUDGET_ADMIN")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED);
        }
    }

    /** 履歴の閲覧: 組織の BUDGET_VIEW。 */
    public void requireBudgetView(Long userId, Long organizationId) {
        if (!isMemberWith(userId, organizationId, "BUDGET_VIEW")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
    }

    private boolean isMemberWith(Long userId, Long organizationId, String permission) {
        return accessControlService.isMember(userId, organizationId, "ORGANIZATION")
                && accessControlService.hasPermission(userId, organizationId, "ORGANIZATION", permission);
    }
}
