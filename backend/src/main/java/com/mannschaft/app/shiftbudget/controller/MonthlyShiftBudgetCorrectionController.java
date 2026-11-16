package com.mannschaft.app.shiftbudget.controller;

import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionHistoryView;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionView;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.RequireFeature;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseCorrectionRequest;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseReversalRequest;
import com.mannschaft.app.shiftbudget.dto.UncountedShiftsResponse;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCorrectionCoordinator;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCorrectionHistoryFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CMP-260909-2152 月次締め後訂正 API（試練段階の骨格）。
 *
 * <ul>
 *   <li>{@code POST .../monthly-closes/{yyyy-MM}/corrections} — 訂正（MODIFY / CANCEL / ADD）登録（組織 BUDGET_ADMIN）</li>
 *   <li>{@code POST .../corrections/{correctionId}/reversals} — 最新訂正の取消（組織 BUDGET_ADMIN）</li>
 *   <li>{@code GET .../corrections} — 締め事実と訂正履歴（組織 BUDGET_VIEW）</li>
 *   <li>{@code GET .../uncounted-shifts} — 締め後に未計上の勤務（組織 BUDGET_ADMIN）</li>
 * </ul>
 * <p>いずれも SYSTEM_ADMIN 単独では拒否する。検証・応答 DTO・実装は出陣で行う。</p>
 */
@RestController
@RequireFeature("FEATURE_SHIFT_ENABLED")
@RequestMapping("/api/v1/shift-budget/allocations/{allocationId}/monthly-closes/{yearMonth}")
@RequiredArgsConstructor
public class MonthlyShiftBudgetCorrectionController {

    private final MonthlyShiftBudgetCorrectionCoordinator coordinator;
    private final MonthlyShiftBudgetCorrectionHistoryFacade historyFacade;

    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ShiftBudgetCorrectionView> correct(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable Long allocationId,
            @PathVariable String yearMonth,
            @RequestBody MonthlyCloseCorrectionRequest request) {
        return ApiResponse.of(coordinator.correct(organizationId, allocationId, yearMonth, request));
    }

    @PostMapping("/corrections/{correctionId}/reversals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ShiftBudgetCorrectionView> reverse(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable Long allocationId,
            @PathVariable String yearMonth,
            @PathVariable String correctionId,
            @RequestBody MonthlyCloseReversalRequest request) {
        return ApiResponse.of(coordinator.reverse(organizationId, allocationId, yearMonth, correctionId, request));
    }

    @GetMapping("/corrections")
    public ApiResponse<ShiftBudgetCorrectionHistoryView> history(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable Long allocationId,
            @PathVariable String yearMonth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.of(historyFacade.history(organizationId, allocationId, yearMonth, page, size));
    }

    @GetMapping("/uncounted-shifts")
    public ApiResponse<UncountedShiftsResponse> uncountedShifts(
            @RequestHeader("X-Organization-Id") Long organizationId,
            @PathVariable Long allocationId,
            @PathVariable String yearMonth) {
        return ApiResponse.of(historyFacade.uncountedShifts(organizationId, allocationId, yearMonth));
    }
}
