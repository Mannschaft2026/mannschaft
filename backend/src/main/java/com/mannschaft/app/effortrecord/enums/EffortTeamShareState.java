package com.mannschaft.app.effortrecord.enums;

/** F06.4.1 チーム側共有行（effort_team_shares.state）の状態（データ設計 §2・§3）。 */
public enum EffortTeamShareState {
    PENDING,
    VISIBLE,
    REVOKED,
    MODERATED
}
