package com.mannschaft.app.effortrecord.enums;

/** F06.4.1 チーム共有の状態（データ設計 §3）。原本側 share_state の値。 */
public enum EffortShareState {
    PENDING,
    VISIBLE,
    FAILED,
    REVOKED,
    MODERATED
}
