package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;

/**
 * チームシフト設定更新リクエストDTO。
 * 少なくとも1つのリマインドが有効である必要がある。
 */
@Getter
public class UpdateTeamShiftSettingsRequest {

    private boolean reminder48hEnabled;
    private boolean reminder24hEnabled;
    private boolean reminder12hEnabled;

    /** 副管理者へのシフト閲覧許可の切替。省略（null）なら変更しない。 */
    private Boolean deputyShiftViewEnabled;

    /** 副管理者への時給閲覧許可の切替。省略（null）なら変更しない。 */
    private Boolean deputyHourlyRateViewEnabled;

    /**
     * カスタムバリデーション: 少なくとも1つのリマインドが有効であること。
     */
    @AssertTrue(message = "少なくとも1つのリマインドを有効にする必要があります")
    public boolean isAtLeastOneEnabled() {
        return reminder48hEnabled || reminder24hEnabled || reminder12hEnabled;
    }
}
