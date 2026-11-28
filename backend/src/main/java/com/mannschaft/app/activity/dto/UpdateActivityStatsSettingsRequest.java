package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 活動統計のスコープ別設定の更新リクエスト（F06.4 §15.3）。新規作成時の {@code version} は null。
 */
@Getter
@RequiredArgsConstructor
public class UpdateActivityStatsSettingsRequest {

    @NotNull
    private final Boolean topParticipantsVisibleToMembers;

    private final Long version;
}
