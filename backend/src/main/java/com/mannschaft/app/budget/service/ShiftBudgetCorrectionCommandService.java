package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.ShiftBudgetJournalSources;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionCommand;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionCommand.ClosedLine;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionOutcome;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionView;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity;
import com.mannschaft.app.budget.entity.BudgetMonthlyCloseRecordEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.budget.repository.BudgetMonthlyCloseCorrectionRepository;
import com.mannschaft.app.budget.repository.BudgetMonthlyCloseRecordRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.KIND_ADD;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.KIND_CANCEL;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.KIND_MODIFY;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.KIND_REVERSAL;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.LINE_ADDED;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.LINE_ORIGINAL;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.STATUS_ABSENT;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.STATUS_CANCELLED;
import static com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity.STATUS_CONFIRMED;

/**
 * CMP-260909-2152 月次締め後訂正の登録（budget ドメインの書込み TX）。
 *
 * <p>TX 規則: 本クラスは budget の Repository だけを使う。shiftbudget の値（締め時点の確定明細・締めイベント）は
 * 段取り役が読取 TX で取得し、{@link ShiftBudgetCorrectionCommand.Snapshot} として値で持ち込む。</p>
 *
 * <p>順序（デッドロックを避けるため年度行→原仕訳→締め記録の固定順）:</p>
 * <ol>
 *   <li>同じ command_id の保存済み結果があれば返す（内容違いは 409）</li>
 *   <li>年度行を {@code FOR UPDATE} で読み、OPEN でなければ BUDGET_004（年度クローズはこのロックを待つ）</li>
 *   <li>原月次仕訳を {@code FOR UPDATE}（生存0件は404、2件以上は409）</li>
 *   <li>ロック待ちの間に同 command が確定した場合に備えて再検索</li>
 *   <li>締め記録を取得（無ければ作成。確定明細の合計・件数を原仕訳と照合）</li>
 *   <li>expected_revision を照合</li>
 *   <li>明細の訂正前後・月合計の差額を計算し、差額仕訳と訂正行を INSERT</li>
 *   <li>締め記録の revision・訂正後合計を更新</li>
 * </ol>
 * <p>原仕訳・元の消化行は書き換えない。訂正は追記だけで、月合計は丸め前の合計（exact_total）を持ち回り、
 * 丸めた値の差額を仕訳にするため、訂正を重ねても丸め誤差が累積しない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetCorrectionCommandService {

    private static final int MAX_CONTENT_OR_REASON = 500;
    /** 追加する勤務は1日まで。既存の明細の訂正は消化行の hours の列幅（5,2）の上限まで。 */
    private static final BigDecimal MAX_ADD_HOURS = new BigDecimal("24");
    private static final BigDecimal MAX_LINE_HOURS = new BigDecimal("999.99");

    private final BudgetTransactionRepository transactionRepository;
    private final BudgetFiscalYearRepository fiscalYearRepository;
    private final BudgetMonthlyCloseRecordRepository recordRepository;
    private final BudgetMonthlyCloseCorrectionRepository correctionRepository;

    /** 明細1行の現在値。 */
    private record LineState(String lineKind, Long consumptionId, UUID addedLineId, Long userId, Long slotId,
                             Long shiftId, LocalDate workDate, BigDecimal hours, BigDecimal hourlyRate,
                             BigDecimal amount, String status) {
        boolean live() {
            return STATUS_CONFIRMED.equals(status);
        }

        /** 月合計に入る金額。 */
        BigDecimal effective() {
            return live() ? amount : BigDecimal.ZERO;
        }
    }

    /**
     * 訂正を1件登録する。
     *
     * @return 登録した訂正行。同 command の再送では保存済みの結果（{@code replayed=true}）
     */
    @Transactional
    public ShiftBudgetCorrectionOutcome record(ShiftBudgetCorrectionCommand cmd) {
        validate(cmd);
        String hash = requestHash(cmd);

        // 1
        Optional<BudgetMonthlyCloseCorrectionEntity> prior = correctionRepository.findByCommandId(cmd.commandId());
        if (prior.isPresent()) {
            return replay(prior.get(), hash);
        }

        // 2 年度行（年度クローズと直列化）
        BudgetTransactionEntity peek = requireSingle(transactionRepository
                .findBySourceTypeAndSourceIdAndTransactionDate(ShiftBudgetJournalSources.MONTHLY,
                        cmd.allocationId(), cmd.originalTransactionDate()));
        BudgetFiscalYearEntity fiscalYear = fiscalYearRepository.findByIdForUpdate(peek.getFiscalYearId())
                .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_001));
        if (fiscalYear.getStatus() != BudgetFiscalYearStatus.OPEN) {
            throw new BusinessException(BudgetErrorCode.BUDGET_004);
        }

        // 3 原月次仕訳
        BudgetTransactionEntity journal = requireSingle(transactionRepository
                .findBySourceForUpdate(ShiftBudgetJournalSources.MONTHLY, cmd.allocationId(),
                        cmd.originalTransactionDate()));

        // 4 ロック待ちの間に同 command が確定していないか（ロック付き読み取りは最新のコミット済み行を読む）
        prior = correctionRepository.findByCommandId(cmd.commandId());
        if (prior.isPresent()) {
            return replay(prior.get(), hash);
        }

        // 5 締め記録
        BudgetMonthlyCloseRecordEntity record = recordRepository
                .findByOriginalTransactionIdForUpdate(journal.getId())
                .orElseGet(() -> createRecord(cmd, journal));

        // 6 expected_revision
        if (cmd.expectedRevision() != record.getRevision()) {
            throw new BusinessException(BudgetErrorCode.BUDGET_026);
        }

        // 7 訂正前後の明細と差額
        Transition t = transition(cmd, record);
        BigDecimal exactDelta = t.after().effective().subtract(t.before().effective()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newExact = record.getExactTotal().add(exactDelta).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newPosted = newExact.setScale(0, RoundingMode.HALF_UP);
        BigDecimal postedDelta = newPosted.subtract(record.getPostedTotal());

        Long journalId = null;
        if (postedDelta.signum() != 0) {
            journalId = transactionRepository.save(correctionJournal(cmd, journal, postedDelta, record.getRevision() + 1))
                    .getId();
        }
        BudgetMonthlyCloseCorrectionEntity saved = correctionRepository.save(BudgetMonthlyCloseCorrectionEntity.builder()
                // ADD 行は自身の ID が追加明細ID。以後の訂正はこの ID で明細を特定する。
                .id(KIND_ADD.equals(t.kind()) ? t.after().addedLineId() : null)
                .closeRecordId(record.getId())
                .revision(record.getRevision() + 1)
                .kind(t.kind())
                .lineKind(t.after().lineKind())
                .consumptionId(t.after().consumptionId())
                .addedLineId(t.after().addedLineId())
                .reversesCorrectionId(t.reverses())
                .userId(t.after().userId())
                .workDate(t.after().workDate())
                .slotId(t.after().slotId())
                .shiftId(t.after().shiftId())
                .beforeHours(t.before().hours())
                .beforeHourlyRate(t.before().hourlyRate())
                .beforeAmount(t.before().amount())
                .beforeLineStatus(t.before().status())
                .afterHours(t.after().hours())
                .afterHourlyRate(t.after().hourlyRate())
                .afterAmount(t.after().amount())
                .afterLineStatus(t.after().status())
                .exactDelta(exactDelta)
                .postedDelta(postedDelta)
                .exactMonthTotalAfter(newExact)
                .content(cmd.content())
                .reason(cmd.reason())
                .actorUserId(cmd.actorUserId())
                .commandId(cmd.commandId())
                .requestHash(hash)
                .budgetTransactionId(journalId)
                .build());

        // 8
        record.applyCorrection(record.getRevision() + 1, newExact, newPosted);
        recordRepository.save(record);

        log.info("シフト予算の締め後訂正を登録: allocId={}, month={}, kind={}, revision={}, postedDelta={}",
                cmd.allocationId(), cmd.targetMonth(), t.kind(), saved.getRevision(), postedDelta);
        return new ShiftBudgetCorrectionOutcome(ShiftBudgetCorrectionView.from(saved), false,
                newPosted.subtract(record.getOriginalAmount()));
    }

    // ─────────────────────────── 訂正前後の明細 ───────────────────────────

    private record Transition(String kind, UUID reverses, LineState before, LineState after) {
    }

    private Transition transition(ShiftBudgetCorrectionCommand cmd, BudgetMonthlyCloseRecordEntity record) {
        switch (cmd.kind()) {
            case KIND_ADD -> {
                UUID lineId = UuidV7Holder.next();
                LineState before = new LineState(LINE_ADDED, null, lineId, cmd.userId(), cmd.slotId(), cmd.shiftId(),
                        cmd.workDate(), BigDecimal.ZERO.setScale(2), null, BigDecimal.ZERO.setScale(2), STATUS_ABSENT);
                BigDecimal hours = scale2(cmd.hours());
                BigDecimal rate = scale2(cmd.hourlyRate());
                LineState after = new LineState(LINE_ADDED, null, lineId, cmd.userId(), cmd.slotId(), cmd.shiftId(),
                        cmd.workDate(), hours, rate, amountOf(rate, hours), STATUS_CONFIRMED);
                requireNoLiveDuplicate(record, cmd, cmd.slotId(), cmd.userId(), null, lineId);
                return new Transition(KIND_ADD, null, before, after);
            }
            case KIND_MODIFY -> {
                LineState cur = currentState(record, cmd, cmd.lineKind(), cmd.consumptionId(), cmd.addedLineId());
                BigDecimal hours = scale2(cmd.hours());
                BigDecimal rate = scale2(cmd.hourlyRate());
                if (cur.live() && cur.hours().compareTo(hours) == 0
                        && cur.hourlyRate() != null && cur.hourlyRate().compareTo(rate) == 0) {
                    throw new BusinessException(BudgetErrorCode.BUDGET_034);
                }
                if (!cur.live()) {
                    requireNoLiveDuplicate(record, cmd, cur.slotId(), cur.userId(), cur.consumptionId(), cur.addedLineId());
                }
                LineState after = withValues(cur, hours, rate, amountOf(rate, hours), STATUS_CONFIRMED);
                return new Transition(KIND_MODIFY, null, cur, after);
            }
            case KIND_CANCEL -> {
                LineState cur = currentState(record, cmd, cmd.lineKind(), cmd.consumptionId(), cmd.addedLineId());
                if (!cur.live()) {
                    throw new BusinessException(BudgetErrorCode.BUDGET_034);
                }
                LineState after = withValues(cur, cur.hours(), cur.hourlyRate(), BigDecimal.ZERO.setScale(2),
                        STATUS_CANCELLED);
                return new Transition(KIND_CANCEL, null, cur, after);
            }
            case KIND_REVERSAL -> {
                BudgetMonthlyCloseCorrectionEntity target = correctionRepository
                        .findByIdAndCloseRecordId(cmd.reversesCorrectionId(), record.getId())
                        .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_028));
                BudgetMonthlyCloseCorrectionEntity latest = latestOf(record, target.getLineKind(),
                        target.getConsumptionId(), target.getAddedLineId());
                if (latest == null || !latest.getId().equals(target.getId())) {
                    throw new BusinessException(BudgetErrorCode.BUDGET_029);
                }
                LineState cur = fromAfter(target);
                LineState after = new LineState(target.getLineKind(), target.getConsumptionId(), target.getAddedLineId(),
                        target.getUserId(), target.getSlotId(), target.getShiftId(), target.getWorkDate(),
                        target.getBeforeHours(), target.getBeforeHourlyRate(), target.getBeforeAmount(),
                        target.getBeforeLineStatus());
                if (!cur.live() && after.live()) {
                    requireNoLiveDuplicate(record, cmd, after.slotId(), after.userId(), after.consumptionId(),
                            after.addedLineId());
                }
                return new Transition(KIND_REVERSAL, target.getId(), cur, after);
            }
            default -> throw new BusinessException(BudgetErrorCode.BUDGET_035);
        }
    }

    private static LineState withValues(LineState base, BigDecimal hours, BigDecimal rate, BigDecimal amount,
                                        String status) {
        return new LineState(base.lineKind(), base.consumptionId(), base.addedLineId(), base.userId(), base.slotId(),
                base.shiftId(), base.workDate(), hours, rate, amount, status);
    }

    private static LineState fromAfter(BudgetMonthlyCloseCorrectionEntity c) {
        return new LineState(c.getLineKind(), c.getConsumptionId(), c.getAddedLineId(), c.getUserId(), c.getSlotId(),
                c.getShiftId(), c.getWorkDate(), c.getAfterHours(), c.getAfterHourlyRate(), c.getAfterAmount(),
                c.getAfterLineStatus());
    }

    /** 明細の現在値（最新の訂正の訂正後。訂正が無い元の明細は締めた時点の値）。無ければ 404。 */
    private LineState currentState(BudgetMonthlyCloseRecordEntity record, ShiftBudgetCorrectionCommand cmd,
                                   String lineKind, Long consumptionId, UUID addedLineId) {
        BudgetMonthlyCloseCorrectionEntity latest = latestOf(record, lineKind, consumptionId, addedLineId);
        if (latest != null) {
            return fromAfter(latest);
        }
        if (LINE_ORIGINAL.equals(lineKind) && consumptionId != null) {
            return cmd.snapshot().lines().stream()
                    .filter(l -> Objects.equals(l.consumptionId(), consumptionId))
                    .findFirst()
                    .map(ShiftBudgetCorrectionCommandService::fromClosedLine)
                    .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_027));
        }
        throw new BusinessException(BudgetErrorCode.BUDGET_027);
    }

    private static LineState fromClosedLine(ClosedLine l) {
        return new LineState(LINE_ORIGINAL, l.consumptionId(), null, l.userId(), l.slotId(), l.shiftId(), null,
                scale2(l.hours()), scale2(l.hourlyRate()), l.amount().setScale(2, RoundingMode.HALF_UP),
                STATUS_CONFIRMED);
    }

    private BudgetMonthlyCloseCorrectionEntity latestOf(BudgetMonthlyCloseRecordEntity record, String lineKind,
                                                        Long consumptionId, UUID addedLineId) {
        if (LINE_ORIGINAL.equals(lineKind) && consumptionId != null) {
            return correctionRepository.findFirstByCloseRecordIdAndConsumptionIdOrderByRevisionDesc(
                    record.getId(), consumptionId).orElse(null);
        }
        if (LINE_ADDED.equals(lineKind) && addedLineId != null) {
            return correctionRepository.findFirstByCloseRecordIdAndAddedLineIdOrderByRevisionDesc(
                    record.getId(), addedLineId).orElse(null);
        }
        return null;
    }

    /**
     * 非生存→生存になる操作（ADD・取消の取消・取消済み明細の訂正）の重複検査。
     * 同じ勤務枠・同じ人の実質生存明細（元の明細の訂正後値と追加明細）と、予定（PLANNED）の消化が無いこと。
     * 勤務枠の無い追加は検査しない。
     */
    private void requireNoLiveDuplicate(BudgetMonthlyCloseRecordEntity record, ShiftBudgetCorrectionCommand cmd,
                                        Long slotId, Long userId, Long selfConsumptionId, UUID selfAddedLineId) {
        if (slotId == null || userId == null) {
            return;
        }
        if (cmd.snapshot().plannedSlotUsers().contains(slotId + ":" + userId)) {
            throw new BusinessException(BudgetErrorCode.BUDGET_031);
        }
        for (ClosedLine line : cmd.snapshot().lines()) {
            if (Objects.equals(line.slotId(), slotId) && Objects.equals(line.userId(), userId)
                    && !Objects.equals(line.consumptionId(), selfConsumptionId)
                    && currentState(record, cmd, LINE_ORIGINAL, line.consumptionId(), null).live()) {
                throw new BusinessException(BudgetErrorCode.BUDGET_030);
            }
        }
        List<BudgetMonthlyCloseCorrectionEntity> adds = correctionRepository
                .findByCloseRecordIdAndKindAndSlotIdAndUserId(record.getId(), KIND_ADD, slotId, userId);
        for (BudgetMonthlyCloseCorrectionEntity add : adds) {
            if (Objects.equals(add.getAddedLineId(), selfAddedLineId)) {
                continue;
            }
            BudgetMonthlyCloseCorrectionEntity latest = latestOf(record, LINE_ADDED, null, add.getAddedLineId());
            if (latest != null && latest.isAfterLive()) {
                throw new BusinessException(BudgetErrorCode.BUDGET_030);
            }
        }
    }

    // ─────────────────────────── 締め記録・仕訳 ───────────────────────────

    private BudgetMonthlyCloseRecordEntity createRecord(ShiftBudgetCorrectionCommand cmd,
                                                        BudgetTransactionEntity journal) {
        BigDecimal exact = cmd.snapshot().lines().stream().map(ClosedLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (exact.setScale(0, RoundingMode.HALF_UP).compareTo(journal.getAmount()) != 0) {
            // 複数月を締めた割当など、確定明細の合計が原仕訳と合わないものは月を特定できない。誤った訂正を作らない。
            log.warn("締め後訂正: 確定明細の合計が原仕訳と一致しない: allocId={}, month={}, lines={}, journal={}",
                    cmd.allocationId(), cmd.targetMonth(), exact, journal.getAmount());
            throw new BusinessException(BudgetErrorCode.BUDGET_033);
        }
        Snapshot2 s = new Snapshot2(cmd);
        return recordRepository.save(BudgetMonthlyCloseRecordEntity.builder()
                .originalTransactionId(journal.getId())
                .allocationId(cmd.allocationId())
                .targetMonth(cmd.targetMonth())
                .originalAmount(journal.getAmount())
                .originalLineCount(cmd.snapshot().lines().size())
                .originalExactTotal(exact)
                .closeActorKind(s.kind())
                .closeActorUserId(s.userId())
                .originalRecordedBy(journal.getRecordedBy())
                .closedAt(cmd.snapshot().closedAt() != null ? cmd.snapshot().closedAt() : journal.getCreatedAt())
                .revision(0L)
                .postedTotal(journal.getAmount())
                .exactTotal(exact)
                .build());
    }

    /** 締めイベントが無い（旧締め）ときは UNKNOWN。割当作成者・仕訳記録者を操作者として出さない。 */
    private record Snapshot2(ShiftBudgetCorrectionCommand cmd) {
        String kind() {
            String k = cmd.snapshot().closeActorKind();
            return k == null ? "UNKNOWN" : k;
        }

        Long userId() {
            return "MANUAL".equals(kind()) ? cmd.snapshot().closeActorUserId() : null;
        }
    }

    private BudgetTransactionEntity correctionJournal(ShiftBudgetCorrectionCommand cmd, BudgetTransactionEntity original,
                                                      BigDecimal postedDelta, long revision) {
        YearMonth ym = YearMonth.parse(cmd.targetMonth());
        return BudgetTransactionEntity.builder()
                .fiscalYearId(original.getFiscalYearId())
                .categoryId(original.getCategoryId())
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .transactionType(BudgetTransactionType.EXPENSE)
                .amount(postedDelta)
                .transactionDate(original.getTransactionDate())
                .title(String.format("%d年%d月 シフト人件費（締め後訂正）", ym.getYear(), ym.getMonthValue()))
                .description("F08.7 月次締め後の訂正による差額仕訳（訂正 revision " + revision + "）")
                .isAutoRecorded(true)
                .sourceType(ShiftBudgetJournalSources.CORRECTION)
                .sourceId(cmd.allocationId())
                .approvalStatus(BudgetApprovalStatus.APPROVED)
                .recordedBy(cmd.actorUserId())
                .build();
    }

    private static BudgetTransactionEntity requireSingle(List<BudgetTransactionEntity> journals) {
        if (journals.isEmpty()) {
            throw new BusinessException(BudgetErrorCode.BUDGET_024);
        }
        if (journals.size() > 1) {
            throw new BusinessException(BudgetErrorCode.BUDGET_025);
        }
        return journals.get(0);
    }

    // ─────────────────────────── 冪等・入力 ───────────────────────────

    private ShiftBudgetCorrectionOutcome replay(BudgetMonthlyCloseCorrectionEntity prior, String hash) {
        if (!prior.getRequestHash().equals(hash)) {
            throw new BusinessException(BudgetErrorCode.BUDGET_032);
        }
        BigDecimal delta = recordRepository.findById(prior.getCloseRecordId())
                .map(r -> r.getPostedTotal().subtract(r.getOriginalAmount()))
                .orElse(BigDecimal.ZERO);
        return new ShiftBudgetCorrectionOutcome(ShiftBudgetCorrectionView.from(prior), true, delta);
    }

    private static void validate(ShiftBudgetCorrectionCommand cmd) {
        boolean bad = cmd.commandId() == null || cmd.allocationId() == null || cmd.kind() == null
                || cmd.reason() == null || cmd.reason().isBlank() || cmd.reason().length() > MAX_CONTENT_OR_REASON
                || (cmd.content() != null && cmd.content().length() > MAX_CONTENT_OR_REASON)
                || cmd.actorUserId() == null || cmd.snapshot() == null || cmd.originalTransactionDate() == null
                || cmd.expectedRevision() < 0;
        if (!bad && (KIND_MODIFY.equals(cmd.kind()) || KIND_ADD.equals(cmd.kind()))) {
            bad = cmd.hours() == null || cmd.hours().signum() <= 0
                    || cmd.hours().compareTo(KIND_ADD.equals(cmd.kind()) ? MAX_ADD_HOURS : MAX_LINE_HOURS) > 0
                    || cmd.hourlyRate() == null || cmd.hourlyRate().signum() < 0
                    || (KIND_ADD.equals(cmd.kind()) && (cmd.hourlyRate().signum() <= 0 || cmd.userId() == null
                    || cmd.workDate() == null));
        }
        if (!bad && (KIND_MODIFY.equals(cmd.kind()) || KIND_CANCEL.equals(cmd.kind()))) {
            bad = !(LINE_ORIGINAL.equals(cmd.lineKind()) && cmd.consumptionId() != null)
                    && !(LINE_ADDED.equals(cmd.lineKind()) && cmd.addedLineId() != null);
        }
        if (!bad && KIND_REVERSAL.equals(cmd.kind())) {
            bad = cmd.reversesCorrectionId() == null;
        }
        if (bad) {
            throw new BusinessException(BudgetErrorCode.BUDGET_035);
        }
    }

    private static String requestHash(ShiftBudgetCorrectionCommand c) {
        String canonical = String.join("|", String.valueOf(c.allocationId()), c.targetMonth(),
                String.valueOf(c.expectedRevision()), c.kind(), String.valueOf(c.lineKind()),
                String.valueOf(c.consumptionId()), String.valueOf(c.addedLineId()),
                String.valueOf(c.reversesCorrectionId()), String.valueOf(c.userId()), String.valueOf(c.workDate()),
                String.valueOf(c.slotId()), String.valueOf(c.shiftId()), plain(c.hours()), plain(c.hourlyRate()),
                String.valueOf(c.content()), c.reason(), String.valueOf(c.actorUserId()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が使えない", e);
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 消化の金額と同じ式・同じ丸め（時給 × 時間、小数2桁 HALF_UP）。 */
    private static BigDecimal amountOf(BigDecimal rate, BigDecimal hours) {
        return rate.multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }

    /** UUIDv7 の採番（追加明細ID は ADD 行自身の ID と同じ値にする）。 */
    private static final class UuidV7Holder {
        static UUID next() {
            return com.mannschaft.app.common.UuidV7.generate();
        }
    }
}
