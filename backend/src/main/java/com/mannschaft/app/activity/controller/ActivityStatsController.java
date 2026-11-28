package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityFieldStatsResponse;
import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.dto.ActivityStatsSettingsResponse;
import com.mannschaft.app.activity.dto.UpdateActivityStatsSettingsRequest;
import com.mannschaft.app.activity.service.ActivityStatsService;
import com.mannschaft.app.activity.service.ActivityScopeAccessGuard;
import com.mannschaft.app.activity.service.ActivityStatsSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;

/**
 * 活動記録統計・エクスポートコントローラー。
 */
@RestController
@RequestMapping("/api/v1/activities")
@Tag(name = "活動統計", description = "F06.4 活動統計・集計・エクスポート")
@RequiredArgsConstructor
public class ActivityStatsController {

    private final ActivityStatsService statsService;
    private final ActivityScopeAccessGuard scopeAccessGuard;
    private final com.mannschaft.app.activity.service.AutomaticActivityListService automaticAccess;
    private final ActivityStatsSettingsService settingsService;

    /**
     * 統計・エクスポートAPIの認可ゲート。対象スコープの中身を読める会員のみ実行可（非会員・非公開チームの
     * MEMBER 未満は部外者と同一の 403 = COMMON_002。CMP-261124-0159 F00 §20.3）。
     *
     * <p>パラメータ validation 後・サービス委譲前に先行で呼ぶ。判定はスコープ種別を網羅的に
     * ディスパッチする {@link ActivityScopeAccessGuard} に委譲する。</p>
     */
    private void checkScopeContentAccess(ActivityScopeType scopeType, Long scopeId) {
        scopeAccessGuard.checkScopeContentAccess(SecurityUtils.getCurrentUserId(), scopeType, scopeId);
    }

    /**
     * 活動統計を取得する（F06.4 §15.2）。そのスコープの ADMIN / DEPUTY_ADMIN / MEMBER だけ。サポーター・ゲスト・部外者・
     * 他テナントは部外者と同一の 403（COMMON_002）、COMMITTEE は常に 403。
     *
     * <p>上位参加者は管理者（ADMIN / DEPUTY_ADMIN）には常に、MEMBER には設定 ON のときだけ見せる。</p>
     */
    @GetMapping("/stats")
    @Operation(summary = "活動統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityStatsResponse>> getStats(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam(value = "template_id", required = false) Long templateId,
            @RequestParam(value = "date_from", required = false) LocalDate dateFrom,
            @RequestParam(value = "date_to", required = false) LocalDate dateTo,
            @RequestParam(value = "granularity", required = false) String granularity) {
        ActivityScopeType scope = ActivityScopeType.valueOf(scopeType);
        Long userId = SecurityUtils.getCurrentUserId();
        scopeAccessGuard.checkRegularMemberOrAbove(userId, scope, scopeId);
        boolean topVisible = scopeAccessGuard.isAdminOrAbove(userId, scope, scopeId)
                || settingsService.topParticipantsVisibleToMembers(scope, scopeId);
        ActivityStatsResponse response = statsService.getStats(
                scope, scopeId, templateId, dateFrom, dateTo, granularity, topVisible);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 活動統計のスコープ別設定を取得する（F06.4 §15.3）。管理者（ADMIN / DEPUTY_ADMIN）だけ。
     */
    @GetMapping("/stats/settings")
    @Operation(summary = "活動統計の設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityStatsSettingsResponse>> getStatsSettings(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId) {
        ActivityScopeType scope = ActivityScopeType.valueOf(scopeType);
        checkScopeAdmin(scope, scopeId);
        return ResponseEntity.ok(ApiResponse.of(settingsService.get(scope, scopeId)));
    }

    /**
     * 活動統計のスコープ別設定を保存する（F06.4 §15.3）。管理者だけ。初回は {@code version=null} で作成し、
     * 版の不一致は 409（楽観ロック競合）。
     */
    @PutMapping("/stats/settings")
    @Operation(summary = "活動統計の設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ActivityStatsSettingsResponse>> updateStatsSettings(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @Valid @RequestBody UpdateActivityStatsSettingsRequest request) {
        ActivityScopeType scope = ActivityScopeType.valueOf(scopeType);
        checkScopeAdmin(scope, scopeId);
        return ResponseEntity.ok(ApiResponse.of(
                settingsService.update(scope, scopeId, SecurityUtils.getCurrentUserId(), request)));
    }

    /** 門を通したうえで管理者（ADMIN / DEPUTY_ADMIN）。満たさなければ 403（COMMON_002）。 */
    private void checkScopeAdmin(ActivityScopeType scope, Long scopeId) {
        Long userId = SecurityUtils.getCurrentUserId();
        checkScopeContentAccess(scope, scopeId);
        scopeAccessGuard.checkAdminOrAbove(userId, scope, scopeId);
    }

    /**
     * カスタムフィールドの集計データを取得する。
     */
    @GetMapping("/stats/fields")
    @Operation(summary = "フィールド集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityFieldStatsResponse>> getFieldStats(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam("template_id") Long templateId,
            @RequestParam("field_key") String fieldKey,
            @RequestParam(value = "period", defaultValue = "MONTH") String period) {
        ActivityScopeType scope = ActivityScopeType.valueOf(scopeType);
        checkScopeContentAccess(scope, scopeId);
        ActivityFieldStatsResponse response = statsService.getFieldStats(
                scope, scopeId, templateId, fieldKey, period);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 活動記録をCSVエクスポートする。
     */
    @GetMapping("/export")
    @Operation(summary = "CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public void exportCsv(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam(value = "template_id", required = false) Long templateId,
            @RequestParam(value = "date_from", required = false) LocalDate dateFrom,
            @RequestParam(value = "date_to", required = false) LocalDate dateTo,
            HttpServletResponse response) {
        ActivityScopeType scope = ActivityScopeType.valueOf(scopeType);
        checkScopeContentAccess(scope, scopeId);
        Long userId = SecurityUtils.getCurrentUserId();
        // CMP-261124-0159 F06.4 §15.5: CSV 出力は管理者（ADMIN / DEPUTY_ADMIN）だけ。MEMBER 以下は 403 COMMON_002
        scopeAccessGuard.checkAdminOrAbove(userId, scope, scopeId);
        // CMP-261124-0158: manual 行も認証済み一覧と同じ可視レベルで絞る（SUPPORTER に MEMBERS_ONLY を書き出さない）
        var levels = automaticAccess.readLevels(userId, scope, scopeId);
        statsService.exportCsvWithAutomaticAccess(
                scope, scopeId, templateId, dateFrom, dateTo, response,
                automaticAccess.actualIds(userId, scope, scopeId),
                levels.visibilities(), userId, levels.systemAdmin());
    }
}
