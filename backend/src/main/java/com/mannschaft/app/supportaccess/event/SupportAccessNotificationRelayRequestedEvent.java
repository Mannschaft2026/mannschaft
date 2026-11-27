package com.mannschaft.app.supportaccess.event;

import java.time.Instant;

/** F10.9 未配送のアプリ内通知 outbox の再配送の依頼（再試行バッチが publish し、AFTER_COMMIT で配送する）。 */
public record SupportAccessNotificationRelayRequestedEvent(Instant requestedAt) {
}
