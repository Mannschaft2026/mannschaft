package com.mannschaft.app.supportaccess.listener;

import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.port.SupportAccessNotificationSender;

/** F10.9 遷移イベントを通知へ（原則5: AFTER_COMMIT リスナー）。試練スタブ。 */
public class SupportAccessNotificationListener {

    private final SupportAccessNotificationSender sender;

    public SupportAccessNotificationListener(SupportAccessNotificationSender sender) {
        this.sender = sender;
    }

    public void onGrantChanged(SupportAccessGrantChangedEvent event) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
