package com.mannschaft.app.shiftbudget.service;

import java.math.BigDecimal;

/**
 * CMP-260909-2152 訂正後の割当金額の算出（reader・閾値で共通の唯一の式）。
 *
 * <ul>
 *   <li>訂正後確定額 = 確定額キャッシュ + 訂正差額合計（0 以上）</li>
 *   <li>訂正後消化額 = max(0, 消化額キャッシュ − 確定額キャッシュ) + 訂正後確定額</li>
 * </ul>
 * <p>訂正の無い割当（差額 0）は max(消化額, 確定額) になる。予定額部分の既存の端数ずれは範囲外。</p>
 */
public final class CorrectedAmounts {

    private CorrectedAmounts() {
    }

    public static BigDecimal confirmed(BigDecimal confirmedCache, BigDecimal correctionDelta) {
        return confirmedCache.add(correctionDelta).max(BigDecimal.ZERO);
    }

    public static BigDecimal consumed(BigDecimal consumedCache, BigDecimal confirmedCache, BigDecimal correctionDelta) {
        return consumedCache.subtract(confirmedCache).max(BigDecimal.ZERO)
                .add(confirmed(confirmedCache, correctionDelta));
    }
}
