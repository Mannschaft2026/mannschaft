package com.mannschaft.app.role.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 再招待の招待が有効になった（仮発行の有効化。CMP-261107-1833 PR3 設計 c・h）。
 *
 * <p>依頼者（招待の宛先）と招待先だけを持ち、招待のハッシュ・平文は持たない。有効化と同じ role のトランザクションで
 * 発行し、コミット後に依頼者へ通知する。</p>
 */
@Getter
public class InviteReissuedEvent extends BaseEvent {

    private final Long requesterUserId;
    private final String scopeType;
    private final Long scopeId;

    public InviteReissuedEvent(Long requesterUserId, String scopeType, Long scopeId) {
        this.requesterUserId = requesterUserId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
