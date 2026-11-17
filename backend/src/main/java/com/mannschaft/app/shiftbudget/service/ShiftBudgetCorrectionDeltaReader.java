package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.budget.service.ShiftBudgetCorrectionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * CMP-260909-2152 割当の月次締め後訂正の差額合計を、budget から値で受け取る読取口。
 *
 * <p>閾値判定の全入口（消化記録・取消の公開リスナー、失敗イベントの再試行、訂正の再判定）が、訂正後の消化額を
 * 同じ式（{@link CorrectedAmounts}）で出すための部品。<b>{@code @Transactional} を付けない</b>：
 * 呼出側は TX に入る前にこの値を取得し、値だけを TX の中へ持ち込む（TX 内で他ドメインを呼ばない）。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetCorrectionDeltaReader {

    private final ShiftBudgetCorrectionQueryService queryService;

    /** 割当の訂正差額合計。訂正の無い割当は 0。 */
    public BigDecimal totalOf(Long allocationId) {
        return queryService.correctionDeltaTotals(List.of(allocationId)).getOrDefault(allocationId, BigDecimal.ZERO);
    }
}
