package com.mannschaft.app.gdpr.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/** サポート閲覧の削除再試行受付。GDPR側の受付コミット後にのみ supportaccess ドメインへ配送する。 */
@Getter
public class SupportAccessPurgeRetryRequestedEvent extends BaseEvent {
    private final Long userId;

    public SupportAccessPurgeRetryRequestedEvent(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("削除対象の指定が不正です");
        }
        this.userId = userId;
    }
}
