package com.mannschaft.app.shiftbudget.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 3段締めの段取り役（@Transactional なし。CMP-260909-2152 確定設計 r12 §3・§7）。
 *
 * <p>全公開メソッドの最初の文で {@code NonTransactionalEntryGuard.assertNoActiveTransaction()} を呼ぶ。
 * 認可は持たない（Facade の責務）。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する。</p>
 */
@Service
public class MonthlyShiftBudgetCloseCoordinator {

    /** 手動締めの本体（認可済み前提）。M 以前の未締め終了済み期間を古い順に先に締める。 */
    public CloseResult close(Long organizationId, YearMonth periodKey) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** バッチ経路の1組織×1期間の締め。 */
    public CloseResult closeFromBatch(Long organizationId, YearMonth periodKey) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 日次締めバッチ本体。businessDate の前日までに終了した期間を、組織あたり最大36キー締める。 */
    public DailyCloseResult closeAll(LocalDate businessDate) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 遅延記録の回収（イベントが PREPARED でなくなった PENDING・リース切れ PENDING）。戻り値は処理件数。 */
    public int recoverDeferrals() {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /**
     * 1組織の締め結果。
     *
     * @param closedAllocations        今回 CLOSED にした割当×期間の件数
     * @param alreadyClosedAllocations 既に締め済みだった件数
     * @param failedAllocations        失敗（ABORTED・例外）件数
     * @param closedConsumptions       CONFIRMED にした消化件数
     */
    public record CloseResult(int closedAllocations, int alreadyClosedAllocations, int failedAllocations,
                              int closedConsumptions) {
    }

    /**
     * 日次バッチの結果。
     *
     * @param processedOrganizationIds 締めを行った組織
     * @param closedPeriodKeys         締めた期間キー数（組織合算）
     * @param closedAllocations        CLOSED にした割当×期間の件数
     * @param failedAllocations        失敗件数
     */
    public record DailyCloseResult(List<Long> processedOrganizationIds, int closedPeriodKeys,
                                   int closedAllocations, int failedAllocations) {
    }
}
