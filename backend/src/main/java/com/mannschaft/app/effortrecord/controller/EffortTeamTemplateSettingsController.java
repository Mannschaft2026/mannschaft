package com.mannschaft.app.effortrecord.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.effortrecord.dto.EffortTemplateSettingsResponse;
import com.mannschaft.app.effortrecord.dto.UpdateEffortTemplateSettingsRequest;
import com.mannschaft.app.effortrecord.service.EffortTemplateSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F06.4.1 チームテンプレートの指標設定 API（API 契約 §7）。
 *
 * <p>試練骨格（PR-2）。認可（既存テンプレート管理権限＋scope 一致）は出陣で Service / AccessGuard に実装する。</p>
 */
@RestController
@RequestMapping(value = "/api/v1/teams/{teamId}/effort-records/template-settings",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "個人の頑張り記録（チーム定義）", description = "F06.4.1 チームテンプレートの指標設定")
@RequiredArgsConstructor
public class EffortTeamTemplateSettingsController {

    private final EffortTemplateSettingsService settingsService;

    @GetMapping("/{templateId}")
    @Operation(summary = "テンプレートの指標設定を取得")
    public ResponseEntity<ApiResponse<EffortTemplateSettingsResponse>> get(
            @PathVariable Long teamId, @PathVariable Long templateId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.get(userId, teamId, templateId)));
    }

    @PutMapping("/{templateId}")
    @Operation(summary = "テンプレートの指標設定を更新（全置換）")
    public ResponseEntity<ApiResponse<EffortTemplateSettingsResponse>> update(
            @PathVariable Long teamId, @PathVariable Long templateId,
            @Valid @RequestBody UpdateEffortTemplateSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.update(userId, teamId, templateId, request)));
    }
}
