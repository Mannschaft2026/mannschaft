package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
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
    @Operation(summary = "チームシフト設定更新（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN でも MANAGE_SHIFTS を持つ DEPUTY_ADMIN でもない")
    public ResponseEntity<TeamShiftSettingsResponse> updateSettings(
            @PathVariable("slug") TeamScopeId scopeId,
            @Valid @RequestBody UpdateTeamShiftSettingsRequest request) {
        Long teamId = scopeId.value();
        teamService.assertActiveTeamExists(teamId);
        // 設定変更はシフト管理の操作（CMP-261125-2114）。ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけ。
        accessControlService.checkAdminOrHasPermissionInScope(
                SecurityUtils.getCurrentUserId(), teamId, SCOPE_TYPE, "MANAGE_SHIFTS");
        return ResponseEntity.ok(settingsService.updateSettings(teamId, request));
    }
}
