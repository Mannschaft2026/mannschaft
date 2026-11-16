package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.budget.service.ShiftBudgetCorrectionQueryService;
import com.mannschaft.app.shiftbudget.dto.AllocationListResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * CMP-260909-2152 割当の reader（一覧・詳細・更新結果）に訂正後の値を重ねる段取り役。
 * <b>{@code @Transactional} を付けない</b>。
 *
 * <p>shiftbudget の読取・更新 TX（{@link ShiftBudgetAllocationService}）の結果を値で受け取り、
 * budget の読取 TX から訂正差額合計を取得して合成する。割当数に依らず budget へのクエリは1本。
 * 金額欄（consumed_amount / confirmed_amount）は訂正後の値になり、{@code correction_delta_total} を添える。
 * 割当の削除は、締め済みの月がある割当を拒否するため、旧締めの締めイベントを補完してから行う。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetAllocationReadFacade {

    private final ShiftBudgetAllocationService allocationService;
    private final ShiftBudgetCorrectionQueryService correctionQueryService;
    private final ShiftBudgetCorrectionSnapshotService snapshotService;

    public AllocationListResponse list(Long organizationId, int page, int size) {
        AllocationListResponse base = allocationService.listAllocations(organizationId, page, size);
        List<Long> ids = base.items().stream().map(AllocationResponse::id).toList();
        Map<Long, BigDecimal> deltas = correctionQueryService.correctionDeltaTotals(ids);
        return AllocationListResponse.builder()
                .items(base.items().stream()
                        .map(a -> corrected(a, deltas.getOrDefault(a.id(), BigDecimal.ZERO)))
                        .toList())
                .page(base.page())
                .size(base.size())
                .total(base.total())
                .build();
    }

    public AllocationResponse get(Long organizationId, Long allocationId) {
        return withCorrection(allocationService.getAllocation(organizationId, allocationId));
    }

    public AllocationResponse update(Long organizationId, Long allocationId, AllocationUpdateRequest request) {
        return withCorrection(allocationService.updateAllocation(organizationId, allocationId, request));
    }

    /**
     * 割当を削除する。締めイベントの無い旧締めの月は、budget に月次締め仕訳があることを確認したうえで
     * 操作者 UNKNOWN の締めイベントを補完し、削除側（割当行ロック）の HAS_CLOSED_MONTHS 判定に載せる。
     * 補完は削除の認可・組織の確認が通った後にだけ行う。
     */
    public void delete(Long organizationId, Long allocationId) {
        allocationService.requireDeletable(organizationId, allocationId);
        for (ShiftBudgetCorrectionQueryService.ClosedMonth month : correctionQueryService.closedMonths(allocationId)) {
            snapshotService.ensureCloseEvent(allocationId, month.month(), month.closedAt());
        }
        allocationService.deleteAllocation(organizationId, allocationId);
    }

    private AllocationResponse withCorrection(AllocationResponse base) {
        return corrected(base, correctionQueryService.overlay(base.id()).correctionDeltaTotal());
    }

    /** 金額欄を訂正後の値にし、差額合計を添える（訂正が無くても確定額が消化額を超えない式を適用する）。 */
    static AllocationResponse corrected(AllocationResponse base, BigDecimal delta) {
        return base.toBuilder()
                .confirmedAmount(CorrectedAmounts.confirmed(base.confirmedAmount(), delta))
                .consumedAmount(CorrectedAmounts.consumed(base.consumedAmount(), base.confirmedAmount(), delta))
                .correctionDeltaTotal(delta)
                .build();
    }
}
