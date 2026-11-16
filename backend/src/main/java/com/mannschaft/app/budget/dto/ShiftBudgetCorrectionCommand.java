package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CMP-260909-2152 月次締め後訂正の登録コマンド（段取り役 → budget の訂正 TX へ値で渡す）。
 *
 * <p>budget の TX は shiftbudget の表を読めないため、締め時点の確定明細・締めイベントは
 * 段取り役が shiftbudget の読取 TX で取得し、{@link Snapshot} として値で持ち込む。</p>
 *
 * @param kind     MODIFY / CANCEL / ADD / REVERSAL
 * @param lineKind ORIGINAL / ADDED（ADD は ADDED、REVERSAL は取り消す訂正の明細で決まるため null 可）
 */
public record ShiftBudgetCorrectionCommand(
        UUID commandId,
        Long allocationId,
        String targetMonth,
        LocalDate originalTransactionDate,
        long expectedRevision,
        String kind,
        String lineKind,
        Long consumptionId,
        UUID addedLineId,
        UUID reversesCorrectionId,
        Long userId,
        LocalDate workDate,
        Long slotId,
        Long shiftId,
        BigDecimal hours,
        BigDecimal hourlyRate,
        String content,
        String reason,
        Long actorUserId,
        Snapshot snapshot
) {

    /**
     * 締め時点の事実（shiftbudget 側の値）。
     *
     * @param closeActorKind   MANUAL / BATCH / UNKNOWN
     * @param closeActorUserId 手動締めの操作者（それ以外は null）
     * @param closedAt         締めた日時（不明なら null）
     * @param lines            確定済み（CONFIRMED）の消化明細
     * @param plannedSlotUsers 同じ割当の生存 PLANNED 消化の「slotId:userId」
     */
    public record Snapshot(
            String closeActorKind,
            Long closeActorUserId,
            Instant closedAt,
            List<ClosedLine> lines,
            Set<String> plannedSlotUsers
    ) {
        public Snapshot {
            lines = lines == null ? List.of() : List.copyOf(lines);
            plannedSlotUsers = plannedSlotUsers == null ? Set.of() : Set.copyOf(plannedSlotUsers);
        }
    }

    /** 締めで確定した消化明細1行（元の値。訂正は別表に追記するためここは変わらない）。 */
    public record ClosedLine(
            Long consumptionId,
            Long userId,
            Long slotId,
            Long shiftId,
            BigDecimal hours,
            BigDecimal hourlyRate,
            BigDecimal amount
    ) {
    }
}
