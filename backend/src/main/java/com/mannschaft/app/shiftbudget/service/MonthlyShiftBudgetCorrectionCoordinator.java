package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionCommand;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionOutcome;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionView;
import com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity;
import com.mannschaft.app.budget.service.ShiftBudgetCorrectionCommandService;
import com.mannschaft.app.budget.service.ShiftBudgetCorrectionQueryService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseCorrectionRequest;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseReversalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * CMP-260909-2152 月次締め後訂正の段取り役。<b>{@code @Transactional} を一切付けない</b>。
 *
 * <p>他ドメインを呼ぶのはこのクラスだけで、各ドメインの TX は自ドメインの Repository のみを使う。値は DTO で渡す。</p>
 * <ol>
 *   <li>認可（組織 BUDGET_ADMIN。SYSTEM_ADMIN 単独は拒否）・機能フラグ・入力検証</li>
 *   <li>shiftbudget の読取 TX で、割当・当月の確定明細・締めイベントを値で取得（他組織の割当は 404）</li>
 *   <li>月次締め仕訳の存在確認。締めイベントの無い旧締めは操作者 UNKNOWN で補完（割当行ロックの TX）</li>
 *   <li>ADD は勤務者の組織所属と勤務枠（この割当の勤務か）を確認</li>
 *   <li>budget の訂正 TX（年度行→原仕訳→締め記録のロック順）で訂正行と差額仕訳を追記</li>
 *   <li>監査ログ、訂正後の消化額での閾値再判定（コミット後）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyShiftBudgetCorrectionCoordinator {

    private static final String SOURCE_AUDIT = "SHIFT_BUDGET_CORRECTION_RECORDED";
    /** 追加する勤務は1日（24時間）まで。既存の明細の訂正は、消化行の上限（hours の列幅）まで許す。 */
    private static final BigDecimal MAX_ADD_HOURS = new BigDecimal("24");
    private static final BigDecimal MAX_LINE_HOURS = new BigDecimal("999.99");

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetScopeAccessPolicy scopePolicy;
    private final ShiftBudgetCorrectionSnapshotService snapshotService;
    private final ShiftBudgetCorrectionCommandService commandService;
    private final ShiftBudgetCorrectionQueryService queryService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final ThresholdAlertEvaluationService thresholdAlertEvaluationService;
    private final ShiftBudgetFailedEventService failedEventService;
    private final PlatformTransactionManager transactionManager;

    /** 訂正（MODIFY / CANCEL / ADD）を登録し、登録した訂正行を返す。 */
    public ShiftBudgetCorrectionView correct(Long organizationId, Long allocationId, String yearMonth,
                                             MonthlyCloseCorrectionRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        scopePolicy.requireBudgetAdmin(actorId, organizationId);
        YearMonth month = parseMonth(yearMonth);
        String kind = request.kind() == null ? "" : request.kind().trim();
        boolean add = BudgetMonthlyCloseCorrectionEntity.KIND_ADD.equals(kind);
        validate(request, kind, month);

        ShiftBudgetCorrectionSnapshotService.Facts facts = loadClosedFacts(organizationId, allocationId, month);
        if (add) {
            if (!accessControlService.isMember(request.userId(), organizationId, "ORGANIZATION")) {
                throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_USER_NOT_FOUND);
            }
            if (request.slotId() != null) {
                snapshotService.requireSlotOfAllocation(organizationId, allocationId, request.slotId(),
                        request.shiftId());
            }
        }
        UUID commandId = parseUuid(request.commandId());
        String lineKind = add ? BudgetMonthlyCloseCorrectionEntity.LINE_ADDED : normalize(request.lineKind());
        UUID addedLineId = request.addedLineId() == null || request.addedLineId().isBlank() ? null
                : parseUuid(request.addedLineId());
        ShiftBudgetCorrectionCommand command = new ShiftBudgetCorrectionCommand(commandId, allocationId,
                month.toString(), month.atEndOfMonth(), request.expectedRevision(), kind, lineKind,
                request.consumptionId(), addedLineId, null, request.userId(), request.workDate(), request.slotId(),
                request.shiftId(), request.hours(), request.hourlyRate(), emptyToNull(request.content()),
                request.reason(), actorId, facts.snapshot());
        return finish(command, facts, organizationId);
    }

    /** 最新の訂正を取り消す（REVERSAL 行を追記する）。 */
    public ShiftBudgetCorrectionView reverse(Long organizationId, Long allocationId, String yearMonth,
                                             String correctionId, MonthlyCloseReversalRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        featureService.requireEnabled(organizationId);
        scopePolicy.requireBudgetAdmin(actorId, organizationId);
        YearMonth month = parseMonth(yearMonth);
        if (request.expectedRevision() == null || request.expectedRevision() < 0
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_INVALID_REQUEST);
        }
        UUID target;
        try {
            target = UUID.fromString(correctionId);
        } catch (IllegalArgumentException e) {
            // 形式の不正な ID は存在しない訂正と同じ（404）
            throw new BusinessException(BudgetErrorCode.BUDGET_028);
        }
        ShiftBudgetCorrectionSnapshotService.Facts facts = loadClosedFacts(organizationId, allocationId, month);
        ShiftBudgetCorrectionCommand command = new ShiftBudgetCorrectionCommand(parseUuid(request.commandId()),
                allocationId, month.toString(), month.atEndOfMonth(), request.expectedRevision(),
                BudgetMonthlyCloseCorrectionEntity.KIND_REVERSAL, null, null, null, target, null, null, null, null,
                null, null, null, request.reason(), actorId, facts.snapshot());
        return finish(command, facts, organizationId);
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** 割当・当月の事実を取得し、月次締め仕訳があることを確認する。旧締めは締めイベントを補完する。 */
    private ShiftBudgetCorrectionSnapshotService.Facts loadClosedFacts(Long organizationId, Long allocationId,
                                                                       YearMonth month) {
        ShiftBudgetCorrectionSnapshotService.Facts facts = snapshotService.snapshot(organizationId, allocationId, month);
        if (!facts.closeEventPresent()) {
            if (!queryService.closeExists(allocationId, month.atEndOfMonth())) {
                throw new BusinessException(BudgetErrorCode.BUDGET_024);
            }
            snapshotService.ensureCloseEvent(allocationId, month.toString(), null);
            facts = snapshotService.snapshot(organizationId, allocationId, month);
        }
        return facts;
    }

    private ShiftBudgetCorrectionView finish(ShiftBudgetCorrectionCommand command,
                                             ShiftBudgetCorrectionSnapshotService.Facts facts, Long organizationId) {
        ShiftBudgetCorrectionOutcome outcome = commandService.record(command);
        ShiftBudgetCorrectionView view = outcome.correction();
        if (!outcome.replayed()) {
            auditLogService.record(SOURCE_AUDIT, command.actorUserId(), null, facts.teamId(), organizationId,
                    null, null, null,
                    String.format("{\"allocation_id\":%d,\"target_month\":\"%s\",\"correction_id\":\"%s\","
                                    + "\"kind\":\"%s\",\"revision\":%d,\"posted_delta\":%s}",
                            command.allocationId(), command.targetMonth(), view.id(), view.kind(), view.revision(),
                            view.postedDelta().toPlainString()));
            reevaluateThresholds(command.allocationId(), organizationId);
        }
        return view;
    }

    /**
     * 訂正のコミット後に、訂正後の消化額で閾値を再判定する。判定の失敗は訂正の成否を変えないが、
     * 握りつぶさず失敗イベントに残して再実行の入口にする。
     */
    private void reevaluateThresholds(Long allocationId, Long organizationId) {
        try {
            ShiftBudgetCorrectionSnapshotService.AllocationAmounts amounts =
                    snapshotService.amounts(organizationId, allocationId);
            BigDecimal delta = queryService.correctionDeltaTotals(java.util.List.of(allocationId))
                    .getOrDefault(allocationId, BigDecimal.ZERO);
            BigDecimal effective = CorrectedAmounts.consumed(amounts.consumed(), amounts.confirmed(), delta);
            // 通知（AFTER_COMMIT）が配送されるよう、再判定は独立 TX（REQUIRES_NEW）で行う。
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status ->
                    thresholdAlertEvaluationService.evaluateWithEffectiveConsumed(allocationId, effective));
        } catch (Exception e) {
            log.error("締め後訂正: 閾値の再判定に失敗（訂正自体は確定済み）: allocId={}", allocationId, e);
            try {
                failedEventService.recordFailure(organizationId, ShiftBudgetFailedEventType.THRESHOLD_ALERT,
                        allocationId, Map.of("allocation_id", allocationId, "reason", "MONTHLY_CLOSE_CORRECTION"),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception recordEx) {
                log.error("締め後訂正: 閾値再判定の失敗イベント記録も失敗: allocId={}", allocationId, recordEx);
            }
        }
    }

    private static void validate(MonthlyCloseCorrectionRequest r, String kind, YearMonth month) {
        boolean ok = r.expectedRevision() != null && r.expectedRevision() >= 0
                && r.reason() != null && !r.reason().isBlank() && r.reason().length() <= 500
                && (r.content() == null || r.content().length() <= 500);
        switch (kind) {
            case BudgetMonthlyCloseCorrectionEntity.KIND_ADD -> ok = ok && r.userId() != null && r.workDate() != null
                    && YearMonth.from(r.workDate()).equals(month)
                    && validHours(r.hours(), MAX_ADD_HOURS) && r.hourlyRate() != null && r.hourlyRate().signum() > 0;
            case BudgetMonthlyCloseCorrectionEntity.KIND_MODIFY -> ok = ok && validHours(r.hours(), MAX_LINE_HOURS)
                    && r.hourlyRate() != null && r.hourlyRate().signum() >= 0 && hasLineTarget(r);
            case BudgetMonthlyCloseCorrectionEntity.KIND_CANCEL -> ok = ok && hasLineTarget(r);
            default -> ok = false;
        }
        if (!ok) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_INVALID_REQUEST);
        }
    }

    private static boolean validHours(BigDecimal hours, BigDecimal max) {
        return hours != null && hours.signum() > 0 && hours.compareTo(max) <= 0;
    }

    private static boolean hasLineTarget(MonthlyCloseCorrectionRequest r) {
        String lineKind = normalize(r.lineKind());
        return (BudgetMonthlyCloseCorrectionEntity.LINE_ORIGINAL.equals(lineKind) && r.consumptionId() != null)
                || (BudgetMonthlyCloseCorrectionEntity.LINE_ADDED.equals(lineKind)
                && r.addedLineId() != null && !r.addedLineId().isBlank());
    }

    private static String normalize(String v) {
        return v == null ? null : v.trim();
    }

    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static YearMonth parseMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_INVALID_REQUEST);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_INVALID_REQUEST);
        }
    }
}
