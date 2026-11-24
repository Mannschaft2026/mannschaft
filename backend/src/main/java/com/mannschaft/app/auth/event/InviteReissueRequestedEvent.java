package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 招待の再発行要請（CMP-261107-1833 設計 §3）。要請者・招待先だけを持ち、招待のハッシュは持たない。
 *
 * <p><b>試練スタブ</b>: 形だけを置いた。</p>
 */
@Getter
public class InviteReissueRequestedEvent extends BaseEvent {

    private final Long requesterUserId;
    private final String scopeType;
    private final Long scopeId;

    public InviteReissueRequestedEvent(Long requesterUserId, String scopeType, Long scopeId) {
        this.requesterUserId = requesterUserId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
