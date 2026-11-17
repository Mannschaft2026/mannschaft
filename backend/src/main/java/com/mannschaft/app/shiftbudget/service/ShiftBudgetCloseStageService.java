package com.mannschaft.app.shiftbudget.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * 3段締めの shiftbudget 側の段（Prepare／Commit／Abort。CMP-260909-2152 確定設計 r12 §3）。
 *
 * <p>各段は shiftbudget の Repository のみに到達する独立 TX。budget のロックを同一 TX で取らない。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する。</p>
 */
@Service
public class ShiftBudgetCloseStageService {

    /** 段1 Prepare。割当 FOR UPDATE → 有効イベント → 勤務枠日付追従 → 対象 PLANNED に印・イベント PREPARED。 */
    public PrepareResult prepare(Long allocationId, YearMonth periodKey) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 段3a Commit（判定 POSTED のみ）。冪等（CLOSED なら何もしない）。件数・合計不一致は TX ロールバック。 */
    public void commit(UUID closeEventId, Long journalTransactionId) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 段3b Abort（判定 ABORTED のみ）。PREPARED なら印を外し ABORTED。 */
    public void abort(UUID closeEventId, String reason) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /**
     * Prepare の結果。
     *
     * @param closeEventId 締めイベント ID（UUIDv7）
     * @param lineCount    印を付けた PLANNED 件数
     * @param plannedTotal 印を付けた PLANNED の合計
     * @param journalDate  仕訳日（min(締め日, 割当終了日)）
     */
    public record PrepareResult(UUID closeEventId, int lineCount, BigDecimal plannedTotal, LocalDate journalDate) {
    }
}
