package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;

/**
 * CMP-260909-2152 訂正 TX の結果。
 *
 * @param correction      登録した（または冪等再送で返す）訂正行
 * @param replayed        同じ command の再送で、既に保存済みの結果を返したか
 * @param correctionDelta 訂正後の締め記録の「月合計の増減」（閾値の再判定用）
 */
public record ShiftBudgetCorrectionOutcome(
        ShiftBudgetCorrectionView correction,
        boolean replayed,
        BigDecimal correctionDelta
) {
}
