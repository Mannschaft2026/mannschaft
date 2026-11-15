package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.shiftbudget.dto.ConsumptionSummaryResponse;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetSummaryFacade;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F08.7 シフト予算 集計 (consumption-summary) 専用コントローラー（Phase 9-δ 第3段で新設）。
 *
 * <p>マスター御裁可 Q7 により {@link ShiftBudgetAllocationController} から責務分離。
 * API パスは互換維持: {@code GET /api/v1/shift-budget/allocations/{id}/consumption-summary}。</p>
 *
 * <p>設計書 F08.7 (v1.2) §6.2.3 / §9.3 に準拠。
 * {@link BudgetView} 階層を {@link MappingJacksonValue} 経由で切り替えることで、
 * フィールド単位のマスキング（個人別時給の漏洩防止）を実現する。</p>
 *
 * <p>権限による View 解決:</p>
 * <ul>
 *   <li>{@code BUDGET_ADMIN} 保有 → {@link BudgetView.BudgetAdmin}（全フィールド公開、{@code by_user} 含む）</li>
 *   <li>{@code BUDGET_VIEW} のみ → {@link BudgetView.BudgetViewer}（{@code by_user} 空配列、flags=BY_USER_HIDDEN）</li>
     *   <li>権限なし → FacadeのPolicyが 403 を返すため到達しない</li>
 * </ul>
 *
 * <p>共通: {@code X-Organization-Id} ヘッダで組織スコープを強制（多テナント分離）。</p>
 */
@Slf4j
@RestController
@RequireFeature("FEATURE_SHIFT_ENABLED")
@RequestMapping("/api/v1/shift-budget/allocations")
@Tag(name = "シフト予算 集計 (F08.7)",
     description = "Phase 9-δ 第3段: 消化サマリ API (BUDGET_ADMIN/VIEW で View 切替)")
@RequiredArgsConstructor
public class ShiftBudgetSummaryController {

    private final ShiftBudgetSummaryFacade summaryFacade;

    /**
     * 指定 allocation の消化サマリを返す。
     *
     * <p>{@link MappingJacksonValue} で {@link BudgetView} を切替えることでフィールド単位のマスキングを行う。
     * FacadeのPolicyでは {@code BUDGET_VIEW} を必須とし、{@code BUDGET_ADMIN} 保有時のみ {@code by_user} に
     * 実データを詰める。Controller 側でも実組織権限で View 階層を選択し、個人別の要素項目を
     * {@code BudgetAdmin} View で保護する。閲覧者にも {@code by_user} の空配列は返す。</p>
     *
     * <p>レスポンス形式は他 API と整合させるため {@code Map.of("data", ...)} で {@code data} ラップ。
     * {@link com.mannschaft.app.common.ApiResponse} は {@code @JsonView} を持たないため
     * {@link MappingJacksonValue} と組み合わせると全フィールドがフィルタアウトされてしまう
     * （Jackson 仕様: {@code DEFAULT_VIEW_INCLUSION=false} がプロジェクト既定）。
     * Map ベースの簡易 wrap で同等のレスポンス形状を維持する。</p>
     */
    @GetMapping("/{id}/consumption-summary")
    @Operation(summary = "シフト予算消化サマリを取得 (BUDGET_ADMIN なら by_user 込み)")
    @ApiResponse(responseCode = "200", description = "消化サマリ",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"),
                    schemaProperties = @SchemaProperty(name = "data",
                            schema = @Schema(implementation = ConsumptionSummaryResponse.class))))
    public MappingJacksonValue getConsumptionSummary(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable("id") Long id) {
        ConsumptionSummaryResponse response = summaryFacade.getConsumptionSummary(organizationId, id);
        // 表示直前もFacadeのPolicyで実所属・VIEW・ADMINを再確認する。
        Class<?> view = summaryFacade.isBudgetAdmin(organizationId)
                ? BudgetView.BudgetAdmin.class : BudgetView.BudgetViewer.class;
        if (view == BudgetView.BudgetViewer.class) {
            // 集計後の権限変更でも、空オブジェクトの配列を返さず閲覧者の形状へ揃える。
            List<String> flags = new ArrayList<>(response.flags());
            if (!flags.contains("BY_USER_HIDDEN")) {
                flags.add("BY_USER_HIDDEN");
            }
            response = response.toBuilder().byUser(List.of()).flags(List.copyOf(flags)).build();
        }

        // ApiResponse 互換の {"data": {...}} 形状を保ちつつ @JsonView を効かせるため Map で wrap
        MappingJacksonValue jacksonValue = new MappingJacksonValue(Map.of("data", response));
        jacksonValue.setSerializationView(view);
        return jacksonValue;
    }

}
