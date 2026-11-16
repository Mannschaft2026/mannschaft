package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shiftbudget.dto.MonthlyCloseCorrectionRequest;
import com.mannschaft.app.shiftbudget.dto.MonthlyCloseReversalRequest;
import org.springframework.stereotype.Component;

/**
 * CMP-260909-2152 月次締め後訂正の段取り役（試練段階の骨格）。
 *
 * <p>確定設計 r2〜r4: 本クラスは {@code @Transactional} を一切付けず、①認可 →
 * ②shiftbudget の readOnly TX（ShiftBudgetCorrectionSnapshotService）で割当・当月明細・締め事実を値で取得 →
 * ③budget の ShiftBudgetCorrectionCommandService.record（budget TX）→ ④監査ログ の順に段取りする。
 * 出陣で実装するまで全メソッドが {@link UnsupportedOperationException} を投げる。
 * 戻り値の型は出陣で応答 DTO に置き換える。</p>
 */
@Component
public class MonthlyShiftBudgetCorrectionCoordinator {

    /** 訂正（MODIFY / CANCEL / ADD）を登録し、登録した訂正行を返す。 */
    public Object correct(Long organizationId, Long allocationId, String yearMonth,
                          MonthlyCloseCorrectionRequest request) {
        throw new UnsupportedOperationException("CMP-260909-2152 未実装: 締め後訂正の登録");
    }

    /** 最新の訂正を取り消す（REVERSAL 行を追記する）。 */
    public Object reverse(Long organizationId, Long allocationId, String yearMonth,
                          String correctionId, MonthlyCloseReversalRequest request) {
        throw new UnsupportedOperationException("CMP-260909-2152 未実装: 締め後訂正の取消");
    }
}
