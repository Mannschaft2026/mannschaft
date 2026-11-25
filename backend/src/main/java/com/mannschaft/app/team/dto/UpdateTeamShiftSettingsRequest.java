package com.mannschaft.app.team.dto;

import lombok.Getter;

/**
 * チームシフト設定更新リクエストDTO。
 *
 * <p>全項目が任意（部分更新）。省略（{@code null}）した項目は<b>変更しない</b>
 * （閲覧切替だけを送る PATCH が、リマインドを false へ上書きしたり 400 になったりしないため。CMP-261125-2114）。
 * 「少なくとも1つのリマインドが有効」という制約は、リクエスト単体ではなく<b>保存後の状態</b>に対して
 * {@code TeamShiftSettingsService#updateSettings} が検証する（400 {@code COMMON_001}）。</p>
 */
@Getter
public class UpdateTeamShiftSettingsRequest {

    /** 48 時間前リマインド。省略（null）なら変更しない。 */
    private Boolean reminder48hEnabled;

    /** 24 時間前リマインド。省略（null）なら変更しない。 */
    private Boolean reminder24hEnabled;

    /** 12 時間前リマインド。省略（null）なら変更しない。 */
    private Boolean reminder12hEnabled;

    /** 副管理者へのシフト閲覧許可の切替。省略（null）なら変更しない。 */
    private Boolean deputyShiftViewEnabled;

    /** 副管理者への時給閲覧許可の切替。省略（null）なら変更しない。 */
    private Boolean deputyHourlyRateViewEnabled;
}
