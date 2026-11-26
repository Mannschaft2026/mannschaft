package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shiftbudget.BudgetAlertAlreadyAcknowledgedException;
import com.mannschaft.app.shiftbudget.dto.AlertAcknowledgeRequest;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.service.BudgetAlertAcknowledgerNameResolver;
import com.mannschaft.app.shiftbudget.service.BudgetThresholdAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F08.7 シフト予算 閾値超過警告 コントローラー（Phase 9-δ 第2段 / API #9 / #10）。
 *
 * <p>設計書 F08.7 (v1.2) §6.1 #9-#10 / §6.2.5 に準拠。</p>
 *
 * <p>API:</p>
 * <ul>
 *   <li>{@code GET  /api/v1/shift-budget/alerts}                      — 未承認警告一覧 (BUDGET_VIEW)</li>
 *   <li>{@code POST /api/v1/shift-budget/alerts/{id}/acknowledge}      — 承認応答 (BUDGET_ADMIN)</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 */
@RestController
@RequireFeature("FEATURE_SHIFT_ENABLED")
@RequestMapping("/api/v1/shift-budget/alerts")
@Tag(name = "シフト予算 警告 (F08.7)",
     description = "Phase 9-δ 第2段: 閾値超過警告 (80/100/120%) 一覧 + 承認応答 API")
@RequiredArgsConstructor
public class BudgetThresholdAlertController {

    private final BudgetThresholdAlertService alertService;
    private final BudgetAlertAcknowledgerNameResolver acknowledgerNameResolver;

    @GetMapping
    @Operation(summary = "未承認の閾値超過警告一覧を取得 (新しい警告ほど上)")
    public ApiResponse<List<AlertResponse>> listAlerts(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.of(alertService.list(organizationId, page, size));
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "閾値超過警告に対して承認応答する (BUDGET_ADMIN 必須)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "確認成功（未確認の警告、または overwrite=true での上書き）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "確認済みの警告を overwrite 指定なしで再確認した（SHIFT_BUDGET_033）。既存の確認者・時刻を details で返す",
            content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.mannschaft.app.shiftbudget.BudgetAlertAlreadyAcknowledgedErrorResponse.class)))
    public ApiResponse<AlertResponse> acknowledgeAlert(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AlertAcknowledgeRequest request) {
        String comment = request != null ? request.comment() : null;
        boolean overwrite = request != null && Boolean.TRUE.equals(request.overwrite());
        try {
            return ApiResponse.of(alertService.acknowledge(organizationId, id, comment, overwrite));
        } catch (BudgetAlertAlreadyAcknowledgedException e) {
            // 409 応答に既存の確認者の氏名を載せる（組織外の確認者は null。判定は resolver に集約）
            String name = acknowledgerNameResolver.resolve(e.getAcknowledgedBy(), organizationId);
            throw e.withAcknowledgedByName(name);
        }
    }
}
