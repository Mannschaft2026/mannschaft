package com.mannschaft.app.role.event;

import com.mannschaft.app.auth.event.InviteReissueRequestedEvent;

/**
 * 再招待要請を招待権限者へ通知するリスナー（CMP-261107-1833 設計 §3）。
 *
 * <p><b>試練スタブ</b>: 署名だけを置いた（Bean 登録・非同期・AFTER_COMMIT・本体は出陣で実装する）。</p>
 */
public class InviteReissueNotificationListener {

    public void onInviteReissueRequested(InviteReissueRequestedEvent event) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }
}
