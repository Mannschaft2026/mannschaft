package com.mannschaft.app.effortrecord.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * F06.4.1 個人原本が本人の操作で論理削除された（commit 後に監査ログ F10.3 へ記録する）。
 *
 * <p>payload は ID と版だけ。体重・科目・メモ・snapshot は載せない（AC-61）。</p>
 */
@Getter
public class EffortRecordDeletedEvent extends BaseEvent {

    private final Long ownerUserId;
    private final UUID recordId;
    private final long version;

    public EffortRecordDeletedEvent(Long ownerUserId, UUID recordId, long version) {
        super();
        this.ownerUserId = ownerUserId;
        this.recordId = recordId;
        this.version = version;
    }
}
