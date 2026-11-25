package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 招待権限者が再招待の依頼を見送った（REISSUE_REQUESTED→REISSUE_DECLINED。CMP-261107-1833 PR3 設計 e・h）。
 *
 * <p>依頼者・招待先だけを持ち、招待のハッシュや表示名は持たない。保留の状態を書いた auth のトランザクションの中で
 * 発行し、コミット後に依頼者へ通知する。</p>
 */
@Getter
public class PendingInviteReissueDeclinedEvent extends BaseEvent {

    private final Long requesterUserId;
    private final String scopeType;
    private final Long scopeId;

    public PendingInviteReissueDeclinedEvent(Long requesterUserId, String scopeType, Long scopeId) {
        this.requesterUserId = requesterUserId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
