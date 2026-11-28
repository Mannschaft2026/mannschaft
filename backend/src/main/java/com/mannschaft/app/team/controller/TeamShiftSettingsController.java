package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeContentAccessGate;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.team.dto.TeamShiftSettingsResponse;
import com.mannschaft.app.team.dto.UpdateTeamShiftSettingsRequest;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import com.mannschaft.app.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * チームシフト設定コントローラー。リマインド間隔カスタマイズのAPIエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{slug}/shift-settings")
@Tag(name = "チームシフト設定")
@RequiredArgsConstructor
public class TeamShiftSettingsController {

    private static final String SCOPE_TYPE = "TEAM";

    private final TeamShiftSettingsService settingsService;
    private final AccessControlService accessControlService;
    private final ScopeContentAccessGate scopeContentAccessGate;
    private final TeamService teamService;

    @GetMapping
    @Operation(summary = "チームシフト設定取得（メンバー限定）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームのメンバーでない")
    public ResponseEntity<TeamShiftSettingsResponse> getSettings(
            @PathVariable("slug") TeamScopeId scopeId) {
        Long teamId = scopeId.value();
        teamService.assertActiveTeamExists(teamId);
        // shift ドメインの既定の流儀（参照=checkMembership / 変更=checkAdminOrAbove。
        // 例: MemberWorkConstraintService / ShiftScheduleService）に揃える。
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), teamId, SCOPE_TYPE);
        return ResponseEntity.ok(settingsService.getSettings(teamId));
    }

    @PatchMapping
    @Operation(summary = "チームシフト設定更新（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN。時給閲覧の切替は ADMIN のみ）",
            description = "いずれも当該チームに在籍中であることが必要（退会済みでロールだけ残った ADMIN は不可）。"
                    + "`deputyHourlyRateViewEnabled`（副管理者への時給閲覧許可）は金額の開示範囲を決める設定のため、"
                    + "チームの ADMIN だけが変更できる（null 以外を送ると、MANAGE_SHIFTS を持つ DEPUTY_ADMIN でも 403）。"
                    + "他の項目（リマインド・`deputyShiftViewEnabled`）は ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN が変更できる。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームに在籍中の ADMIN でも MANAGE_SHIFTS を持つ DEPUTY_ADMIN でもない、"
                    + "または時給閲覧の切替を ADMIN 以外が送った")
    public ResponseEntity<TeamShiftSettingsResponse> updateSettings(
            @PathVariable("slug") TeamScopeId scopeId,
            @Valid @RequestBody UpdateTeamShiftSettingsRequest request) {
        Long teamId = scopeId.value();
        teamService.assertActiveTeamExists(teamId);
        // 設定変更はシフト管理の操作（CMP-261125-2114）。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけ。
        accessControlService.checkAdminOrHasPermissionInScope(
                SecurityUtils.getCurrentUserId(), teamId, SCOPE_TYPE, "MANAGE_SHIFTS");
        Long userId = SecurityUtils.getCurrentUserId();
        // ADMIN の判定（isAdmin）は user_roles だけを見て在籍を問わない。退会済みでロールだけ残った ADMIN を通さないため、
        // 在籍中であることも要求する（シフト管理ゲート ShiftManageAccessGate と同じ基準）。
        // 中身を返さない書き込みなので共通の門の書き込み用（F00 §20.5.1）。
        scopeContentAccessGate.checkMembershipForWrite(userId, teamId, SCOPE_TYPE);
        // 時給を見せる範囲の切替は ADMIN だけ（マスター裁定。MANAGE_SHIFTS 付き DEPUTY は変更不可）。
        // 送った（null 以外）時点で拒否する: 現在値と同じ値の再送でも、権限の無い者に変更の窓口を開けない。
        if (request.getDeputyHourlyRateViewEnabled() != null
                && !accessControlService.isAdmin(userId, teamId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(settingsService.updateSettings(teamId, request));
    }
}
