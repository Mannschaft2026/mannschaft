package com.mannschaft.app.shiftbudget.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 遅延記録（shift_budget_record_deferrals）の確保・延長・確定・再実行（CMP-260909-2152 確定設計 r12 §4.4）。
 *
 * <p>確保・延長・確定は DB の NOW() と所有者（claimed_by）で判定する。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する。</p>
 */
@Service
public class ShiftBudgetRecordDeferralService {

    /** 確保 → 再実行 → 確定。確保できなければ {@link ReplayOutcome#NOT_CLAIMED}、確定が0行なら {@link ReplayOutcome#DISCARDED}。 */
    public ReplayOutcome replay(UUID deferralId, String owner) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** リース延長。0行（所有喪失）なら false。 */
    public boolean extendLease(UUID deferralId, String owner) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 再実行の結果。 */
    public enum ReplayOutcome {
        NOT_CLAIMED,
        REPLAYED,
        REDEFERRED,
        FAILED,
        DISCARDED
    }
}
