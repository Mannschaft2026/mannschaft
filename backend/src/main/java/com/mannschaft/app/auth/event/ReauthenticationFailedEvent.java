package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.PasskeyManageReauthMethod;
import com.mannschaft.app.auth.ReauthPurpose;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 再認証失敗イベント（CMP-261021-1338 B7。監査ログに記録する。失敗 TX のロールバックで消えないよう記録すること）。
 */
@Getter
public class ReauthenticationFailedEvent extends BaseEvent {

    private final Long userId;
    private final ReauthPurpose purpose;
    private final PasskeyManageReauthMethod method;

    public ReauthenticationFailedEvent(Long userId, ReauthPurpose purpose, PasskeyManageReauthMethod method) {
        super();
        this.userId = userId;
        this.purpose = purpose;
        this.method = method;
    }
}
