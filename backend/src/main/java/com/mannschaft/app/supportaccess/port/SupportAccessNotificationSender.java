package com.mannschaft.app.supportaccess.port;

import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;

import java.util.UUID;

/** F10.9 通知の送り口（実装役が NotificationDeliveryRunner へ委譲する。依頼理由はプレーンテキストで表示する）。 */
public interface SupportAccessNotificationSender {

    void send(Long recipientUserId, SupportAccessNotificationKind kind, UUID grantId);
}
