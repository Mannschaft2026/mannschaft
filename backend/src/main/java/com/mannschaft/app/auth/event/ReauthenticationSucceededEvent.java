package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.PasskeyManageReauthMethod;
import com.mannschaft.app.auth.ReauthPurpose;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 再認証成功イベント（CMP-261021-1338 B7。監査ログに記録する）。
 *
 * <p>試練スタブ: 形だけを先に置く（発行・監査記録は出陣で実装する）。</p>
 */
@Getter
public class ReauthenticationSucceededEvent extends BaseEvent {

    private final Long userId;
    private final ReauthPurpose purpose;
    private final PasskeyManageReauthMethod method;

    public ReauthenticationSucceededEvent(Long userId, ReauthPurpose purpose, PasskeyManageReauthMethod method) {
        super();
        this.userId = userId;
        this.purpose = purpose;
        this.method = method;
    }
}
