package com.mannschaft.app.role.service;

/**
 * 招待発行権限の判定（ADMIN、または INVITE_MEMBERS 権限を持つ DEPUTY_ADMIN）。CMP-261107-1833 設計 §9-2。
 * MembershipInviteService の private canIssueWithRole をここへ移して委譲する。{@code @Transactional} を持たない。
 *
 * <p><b>試練スタブ</b>: 契約だけを置いた。振る舞いは出陣で実装する。</p>
 */
public class InviteIssuerPolicy {

    public boolean canIssue(Long userId, Long scopeId, String scopeType) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    public boolean canIssueWithRole(Long userId, Long scopeId, String scopeType, String roleName) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }
}
