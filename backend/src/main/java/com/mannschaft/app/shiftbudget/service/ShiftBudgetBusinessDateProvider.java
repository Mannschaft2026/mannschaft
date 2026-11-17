package com.mannschaft.app.shiftbudget.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * シフト予算の「今日」（Asia/Tokyo の業務日）を返す（CMP-260909-2152 確定設計 r12 §2）。
 *
 * <p>未終了期間の締め拒否（SHIFT_BUDGET_036）、締め日変更の受理条件（今日を含む期間キー）、
 * {@code min_effective_period_key} の算出は、必ずこの Bean から今日を得ること（試練が spy で日付を固定する）。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する（{@code wallClock} から {@code LocalDate.now}）。</p>
 */
@Component
public class ShiftBudgetBusinessDateProvider {

    /** Asia/Tokyo の今日。 */
    public LocalDate today() {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }
}
