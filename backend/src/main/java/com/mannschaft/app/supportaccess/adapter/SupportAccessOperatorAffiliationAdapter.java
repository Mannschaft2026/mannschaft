package com.mannschaft.app.supportaccess.adapter;

import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F10.9 運営アカウントがテナントに所属しているかの判定（運営専用アカウントの原則・SUPPORT_ACCESS_010）。
 *
 * <p>判定は戦役 Z-A PR2 の唯一の窓口 {@link PlatformAccountAffiliationPolicy#hasAnyTenantAffiliation} へ委譲する。
 * チーム・組織・委員会・村・サポーター・個人フォローの所属（と本人の申請による所属待ち）を、アカウントの状態を問わず
 * （停止中の運営アカウントも）数える。所属の範囲を変えるときは Policy 側を直す（本クラスで独自に数えない）。</p>
 */
@Component
@RequiredArgsConstructor
public class SupportAccessOperatorAffiliationAdapter implements SupportAccessOperatorAffiliationPort {

    private final PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;

    @Override
    public boolean isAffiliatedWithAnyTenant(Long operatorUserId) {
        if (operatorUserId == null) {
            return false;
        }
        return platformAccountAffiliationPolicy.hasAnyTenantAffiliation(operatorUserId);
    }
}
