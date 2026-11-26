package com.mannschaft.app.supportaccess.adapter;

import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F10.9 運営アカウントがテナントに所属しているかの判定（運営専用アカウントの原則・SUPPORT_ACCESS_010）。
 *
 * <p>現時点では所属スコープの列挙の正本窓口 {@link MembershipScopeQueryService} で、
 * チーム・組織の所属（user_roles ∪ memberships。離脱していない membership を含む）を判定する。
 * 村・サポーター（フォロー）・委員会の所属判定は、戦役 Z-A PR2 の
 * {@code PlatformAccountAffiliationPolicy}（PR2 未マージ）が持つ。PR2 のマージ後に本クラスの判定を
 * そちらへ委譲して結線する（本ポートのインタフェースは変えない）。</p>
 */
@Component
@RequiredArgsConstructor
public class SupportAccessOperatorAffiliationAdapter implements SupportAccessOperatorAffiliationPort {

    private final MembershipScopeQueryService membershipScopeQueryService;

    @Override
    public boolean isAffiliatedWithAnyTenant(Long operatorUserId) {
        if (operatorUserId == null) {
            return false;
        }
        return !membershipScopeQueryService.findActiveTeamIds(operatorUserId).isEmpty()
                || !membershipScopeQueryService.findActiveOrganizationIds(operatorUserId).isEmpty()
                || !membershipScopeQueryService.findCurrentMembershipTeamIds(operatorUserId).isEmpty()
                || !membershipScopeQueryService.findCurrentMembershipOrganizationIds(operatorUserId).isEmpty();
    }
}
