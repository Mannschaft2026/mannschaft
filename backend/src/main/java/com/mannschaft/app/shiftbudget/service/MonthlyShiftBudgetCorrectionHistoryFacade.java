package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionHistoryView;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionOverlay;
import com.mannschaft.app.budget.service.ShiftBudgetCorrectionQueryService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.UncountedShiftResponse;
import com.mannschaft.app.shiftbudget.dto.UncountedShiftsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CMP-260909-2152 締め後訂正の読取段取り役（{@code @Transactional} を付けない）。
 *
 * <p>履歴（締め事実・訂正行）と「締め後に未計上の勤務」候補を、shiftbudget の値と budget の訂正値を
 * 合成して返す。新しい表は作らない。履歴は組織 BUDGET_VIEW、未計上の勤務は追加（ADD）と同じ組織 BUDGET_ADMIN。
 * SYSTEM_ADMIN 単独は拒否する。</p>
 */
@Component
@RequiredArgsConstructor
public class MonthlyShiftBudgetCorrectionHistoryFacade {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetScopeAccessPolicy scopePolicy;
    private final ShiftBudgetCorrectionSnapshotService snapshotService;
    private final ShiftBudgetCorrectionQueryService queryService;

    /** 締め事実と訂正履歴（revision 昇順、1ページ最大100件）。 */
    public ShiftBudgetCorrectionHistoryView history(Long organizationId, Long allocationId, String yearMonth,
                                                    int page, int size) {
        featureService.requireEnabled(organizationId);
        scopePolicy.requireBudgetView(SecurityUtils.getCurrentUserId(), organizationId);
        YearMonth month = parseMonth(yearMonth);
        ShiftBudgetCorrectionSnapshotService.Facts facts = snapshotService.snapshot(organizationId, allocationId, month);
        return queryService.history(allocationId, month.toString(), month.atEndOfMonth(), page, size, facts.snapshot());
    }

    /** 締め済み月で CONFIRMED も生存 ADD も無い勤務（ワンクリック追加の候補）。 */
    public UncountedShiftsResponse uncountedShifts(Long organizationId, Long allocationId, String yearMonth) {
        featureService.requireEnabled(organizationId);
        scopePolicy.requireBudgetAdmin(SecurityUtils.getCurrentUserId(), organizationId);
        YearMonth month = parseMonth(yearMonth);
        List<UncountedShiftResponse> candidates = snapshotService.uncountedCandidates(organizationId, allocationId, month);
        if (!queryService.closeExists(allocationId, month.atEndOfMonth())) {
            throw new BusinessException(BudgetErrorCode.BUDGET_024);
        }
        ShiftBudgetCorrectionOverlay overlay = queryService.overlay(allocationId);
        Set<String> added = new HashSet<>();
        for (ShiftBudgetCorrectionOverlay.AddedLine line : overlay.addedLines()) {
            if (line.live() && line.slotId() != null) {
                added.add(line.slotId() + ":" + line.userId());
            }
        }
        return new UncountedShiftsResponse(candidates.stream()
                .filter(c -> !added.contains(c.slotId() + ":" + c.userId()))
                .toList(), queryService.currentRevision(allocationId, month.atEndOfMonth()));
    }

    private static YearMonth parseMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_INVALID_REQUEST);
        }
    }
}
