package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 招待発行権限の判定（ADMIN、または INVITE_MEMBERS 権限を持つ DEPUTY_ADMIN）。CMP-261107-1833 設計 §9-2。
 *
 * <p>{@link MembershipInviteService} の private な判定をここへ移し、承諾型招待の発行と再招待要請の通知先解決
 * （{@link InviteReissueRecipientResolver}）が同じ判定を使う。{@code @Transactional} を持たない
 * （判定の照会は {@link AccessControlService} の各メソッドの境界に任せる）。</p>
 */
@Component
@RequiredArgsConstructor
public class InviteIssuerPolicy {

    static final String INVITE_MEMBERS_PERMISSION = "INVITE_MEMBERS";

    private final AccessControlService accessControlService;

    /** 利用者が当該スコープで招待を発行できるか（現在のロール名を引いて判定する）。 */
    public boolean canIssue(Long userId, Long scopeId, String scopeType) {
        String roleName = accessControlService.getRoleName(userId, scopeId, scopeType);
        return canIssueWithRole(userId, scopeId, scopeType, roleName);
    }

    /** ロール名が既に分かっているときの判定。ADMIN は可、DEPUTY_ADMIN は INVITE_MEMBERS 権限があるときだけ可。 */
    public boolean canIssueWithRole(Long userId, Long scopeId, String scopeType, String roleName) {
        if ("ADMIN".equals(roleName)) {
            return true;
        }
        if ("DEPUTY_ADMIN".equals(roleName)) {
            return accessControlService.hasPermission(userId, scopeId, scopeType, INVITE_MEMBERS_PERMISSION);
        }
        return false;
    }
}
