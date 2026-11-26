package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 予算警告の確認済み 409（CMP-260910-1607 ③）に載せる、既存の確認者の氏名を解決する。
 *
 * <p>氏名の解決は他ドメイン（common の {@link NameResolverService} / {@link AccessControlService}）に届くため、
 * {@code @Transactional} の Service には置かない（D-3T）。非トランザクションのこの部品で行い、Controller は
 * この部品だけに依存する（Controller のスライステストで認可型の型を mock せずに済ませるため）。</p>
 */
@Component
@RequiredArgsConstructor
public class BudgetAlertAcknowledgerNameResolver {

    private final NameResolverService nameResolverService;
    private final AccessControlService accessControlService;

    /**
     * 確認者の氏名を返す。氏名を載せるのは、その人がこの組織のメンバーのときだけ。
     * 組織外の確認者（組織外 SYSTEM_ADMIN など）の氏名は漏らさず null にする（画面側は「別の管理者」と表示する）。
     *
     * @return 氏名。確認者が不明・組織外・氏名を引けない場合は null
     */
    public String resolve(Long acknowledgedBy, Long organizationId) {
        if (acknowledgedBy == null || !accessControlService.isMember(acknowledgedBy, organizationId, "ORGANIZATION")) {
            return null;
        }
        return nameResolverService.resolveUserFullNames(List.of(acknowledgedBy)).get(acknowledgedBy);
    }
}
