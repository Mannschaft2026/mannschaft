package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CMP-260909-2152 割当ひとつぶんの訂正の重ね合わせ（reader が訂正後値を出すための値）。
 *
 * @param correctionDeltaTotal 訂正による月合計の増減（Σ 訂正後の月合計 − 締めた時点の月合計）
 * @param correctedLines       訂正された元の確定明細の現在値（消化行ID → 値）。取消済みは live=false
 * @param addedLines           締め後に追加した明細の現在値（取消済みを含む）
 */
public record ShiftBudgetCorrectionOverlay(
        BigDecimal correctionDeltaTotal,
        Map<Long, Line> correctedLines,
        List<AddedLine> addedLines
) {

    public static final ShiftBudgetCorrectionOverlay EMPTY =
            new ShiftBudgetCorrectionOverlay(BigDecimal.ZERO, Map.of(), List.of());

    public ShiftBudgetCorrectionOverlay {
        correctionDeltaTotal = correctionDeltaTotal == null ? BigDecimal.ZERO : correctionDeltaTotal;
        correctedLines = correctedLines == null ? Map.of() : Map.copyOf(correctedLines);
        addedLines = addedLines == null ? List.of() : List.copyOf(addedLines);
    }

    /** 訂正後の明細の現在値。live=false（取消）のとき amount / hours は集計に入れない。 */
    public record Line(boolean live, BigDecimal hours, BigDecimal hourlyRate, BigDecimal amount) {
    }

    /** 締め後に追加した明細の現在値。 */
    public record AddedLine(UUID id, Long userId, Long slotId, boolean live, BigDecimal hours, BigDecimal hourlyRate,
                            BigDecimal amount) {
    }
}
