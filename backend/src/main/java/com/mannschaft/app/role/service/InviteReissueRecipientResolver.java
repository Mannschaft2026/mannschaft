package com.mannschaft.app.role.service;

import java.util.List;

/**
 * 再招待要請の通知先（招待権限者）を解決する（CMP-261107-1833 設計 §3・§9-2）。{@code @Transactional} を持たない。
 *
 * <p><b>試練スタブ</b>: 契約だけを置いた。振る舞いは出陣で実装する。</p>
 */
public class InviteReissueRecipientResolver {

    public List<Long> findInviterUserIds(String scopeType, Long scopeId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }
}
