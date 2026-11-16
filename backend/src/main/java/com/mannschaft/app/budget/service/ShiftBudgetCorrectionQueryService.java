package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.ShiftBudgetJournalSources;
import com.mannschaft.app.budget.dto.ShiftBudgetCloseView;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionCommand.Snapshot;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionHistoryView;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionOverlay;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionView;
import com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity;
import com.mannschaft.app.budget.entity.BudgetMonthlyCloseRecordEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetMonthlyCloseCorrectionRepository;
import com.mannschaft.app.budget.repository.BudgetMonthlyCloseRecordRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CMP-260909-2152 月次締め後訂正の読取（budget ドメインの読取 TX）。
 *
 * <p>reader（割当一覧・詳細・更新結果・summary・個人別・時給別）が「訂正後の値」を出すための値と、
 * 履歴画面の締め事実・訂正履歴を返す。割当数・訂正件数に依らずクエリ数が一定になるよう、
 * 集計は割当IDのまとめ引き、明細は「各明細の最新の訂正行」だけを読む。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftBudgetCorrectionQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final BudgetTransactionRepository transactionRepository;
    private final BudgetMonthlyCloseRecordRepository recordRepository;
    private final BudgetMonthlyCloseCorrectionRepository correctionRepository;

    /** 割当ごとの訂正差額合計。訂正の無い割当は含めない（呼出側は 0 と扱う）。割当数に依らず1クエリ。 */
    public Map<Long, BigDecimal> correctionDeltaTotals(Collection<Long> allocationIds) {
        if (allocationIds == null || allocationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Object[] row : recordRepository.sumCorrectionDeltaByAllocationIds(allocationIds)) {
            totals.put((Long) row[0], (BigDecimal) row[1]);
        }
        return totals;
    }

    /** 割当ひとつぶんの訂正の重ね合わせ（訂正された元の明細の現在値・追加明細・差額合計）。 */
    public ShiftBudgetCorrectionOverlay overlay(Long allocationId) {
        List<BudgetMonthlyCloseRecordEntity> records = recordRepository.findByAllocationId(allocationId);
        if (records.isEmpty()) {
            return ShiftBudgetCorrectionOverlay.EMPTY;
        }
        BigDecimal delta = records.stream().map(r -> r.getPostedTotal().subtract(r.getOriginalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, ShiftBudgetCorrectionOverlay.Line> corrected = new HashMap<>();
        List<ShiftBudgetCorrectionOverlay.AddedLine> added = new ArrayList<>();
        for (BudgetMonthlyCloseCorrectionEntity c : correctionRepository
                .findLatestPerLine(records.stream().map(BudgetMonthlyCloseRecordEntity::getId).toList())) {
            if (BudgetMonthlyCloseCorrectionEntity.LINE_ORIGINAL.equals(c.getLineKind())) {
                corrected.put(c.getConsumptionId(), new ShiftBudgetCorrectionOverlay.Line(c.isAfterLive(),
                        c.getAfterHours(), c.getAfterHourlyRate(), c.getAfterAmount()));
            } else {
                added.add(new ShiftBudgetCorrectionOverlay.AddedLine(c.getAddedLineId(), c.getUserId(), c.getSlotId(),
                        c.isAfterLive(), c.getAfterHours(), c.getAfterHourlyRate(), c.getAfterAmount()));
            }
        }
        return new ShiftBudgetCorrectionOverlay(delta, corrected, added);
    }

    /** 割当が締めた月（旧締めの締めイベント補完用）。 */
    public record ClosedMonth(String month, java.time.Instant closedAt) {
    }

    /** 月次締め仕訳がある月の一覧（月次締めの原仕訳の日付の月と、仕訳の作成日時）。 */
    public List<ClosedMonth> closedMonths(Long allocationId) {
        return transactionRepository.findBySourceTypeAndSourceId(ShiftBudgetJournalSources.MONTHLY, allocationId)
                .stream()
                .map(t -> new ClosedMonth(java.time.YearMonth.from(t.getTransactionDate()).toString(),
                        t.getCreatedAt().atZone(com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant()))
                .toList();
    }

    /** 締め後訂正の現在の通し番号（締め記録が無ければ 0）。ADD の expected_revision に使う。 */
    public long currentRevision(Long allocationId, LocalDate monthEnd) {
        return transactionRepository.findBySourceTypeAndSourceIdAndTransactionDate(
                        ShiftBudgetJournalSources.MONTHLY, allocationId, monthEnd).stream()
                .findFirst()
                .flatMap(t -> recordRepository.findByOriginalTransactionId(t.getId()))
                .map(BudgetMonthlyCloseRecordEntity::getRevision)
                .orElse(0L);
    }

    /** 対象月の月次締め仕訳があるか。 */
    public boolean closeExists(Long allocationId, LocalDate monthEnd) {
        return !transactionRepository.findBySourceTypeAndSourceIdAndTransactionDate(
                ShiftBudgetJournalSources.MONTHLY, allocationId, monthEnd).isEmpty();
    }

    /**
     * 締め事実と訂正履歴（revision 昇順）。締め記録がまだ無い月（訂正前）は、原仕訳と shiftbudget の値から
     * 「締めた事実」を合成して返す（履歴は空）。月次締め仕訳が無ければ 404、複数あれば 409。
     */
    public ShiftBudgetCorrectionHistoryView history(Long allocationId, String targetMonth, LocalDate monthEnd,
                                                    int page, int size, Snapshot facts) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        List<BudgetTransactionEntity> journals = transactionRepository.findBySourceTypeAndSourceIdAndTransactionDate(
                ShiftBudgetJournalSources.MONTHLY, allocationId, monthEnd);
        if (journals.isEmpty()) {
            throw new BusinessException(BudgetErrorCode.BUDGET_024);
        }
        if (journals.size() > 1) {
            throw new BusinessException(BudgetErrorCode.BUDGET_025);
        }
        BudgetTransactionEntity journal = journals.get(0);
        Optional<BudgetMonthlyCloseRecordEntity> found = recordRepository.findByOriginalTransactionId(journal.getId());
        if (found.isEmpty()) {
            return new ShiftBudgetCorrectionHistoryView(new ShiftBudgetCloseView(journal.getAmount(),
                    facts.lines().size(), facts.closedAt() != null ? facts.closedAt()
                            : journal.getCreatedAt().atZone(com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant(),
                    facts.closeActorKind() != null ? facts.closeActorKind() : "UNKNOWN",
                    "MANUAL".equals(facts.closeActorKind()) ? facts.closeActorUserId() : null, 0L,
                    journal.getAmount(), 0, BigDecimal.ZERO, BigDecimal.ZERO),
                    List.of(), safePage, safeSize, 0L);
        }
        BudgetMonthlyCloseRecordEntity record = found.get();
        int addedCount = 0;
        BigDecimal addedAmount = BigDecimal.ZERO;
        for (BudgetMonthlyCloseCorrectionEntity c : correctionRepository.findLatestPerLine(List.of(record.getId()))) {
            if (BudgetMonthlyCloseCorrectionEntity.LINE_ADDED.equals(c.getLineKind()) && c.isAfterLive()) {
                addedCount++;
                addedAmount = addedAmount.add(c.getAfterAmount());
            }
        }
        Page<BudgetMonthlyCloseCorrectionEntity> items = correctionRepository
                .findByCloseRecordIdOrderByRevisionAsc(record.getId(), PageRequest.of(safePage, safeSize));
        ShiftBudgetCloseView close = new ShiftBudgetCloseView(record.getOriginalAmount(), record.getOriginalLineCount(),
                record.getClosedAt(), record.getCloseActorKind(), record.getCloseActorUserId(), record.getRevision(),
                record.getPostedTotal(), addedCount, addedAmount.setScale(2, java.math.RoundingMode.HALF_UP),
                record.getPostedTotal().subtract(record.getOriginalAmount()));
        return new ShiftBudgetCorrectionHistoryView(close,
                items.getContent().stream().map(ShiftBudgetCorrectionView::from).toList(),
                safePage, safeSize, items.getTotalElements());
    }
}
