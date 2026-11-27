package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 活動統計のスコープ別設定（F06.4 §15.3）。行が無いスコープは既定（OFF）で {@code version} は null。
 */
@Getter
@RequiredArgsConstructor
public class ActivityStatsSettingsResponse {

    private final String scopeType;
    private final Long scopeId;
    private final boolean topParticipantsVisibleToMembers;
    private final Long version;
}
