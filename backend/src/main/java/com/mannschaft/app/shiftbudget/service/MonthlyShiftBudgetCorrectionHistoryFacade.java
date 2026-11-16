package com.mannschaft.app.shiftbudget.service;

import org.springframework.stereotype.Component;

/**
 * CMP-260909-2152 締め後訂正の読取段取り役（試練段階の骨格。{@code @Transactional} を付けない）。
 *
 * <p>履歴（締め事実・訂正行）と「締め後に未計上の勤務」候補を、shiftbudget の値と budget の
 * 訂正値を合成して返す。出陣で実装するまで {@link UnsupportedOperationException} を投げる。
 * 戻り値の型は出陣で応答 DTO に置き換える。</p>
 */
@Component
public class MonthlyShiftBudgetCorrectionHistoryFacade {

    /** 締め事実と訂正履歴（revision 昇順、1ページ最大100件）。 */
    public Object history(Long organizationId, Long allocationId, String yearMonth, int page, int size) {
        throw new UnsupportedOperationException("CMP-260909-2152 未実装: 締め後訂正の履歴");
    }

    /** 締め済み月で CONFIRMED も生存 ADD も無い勤務（ワンクリック追加の候補）。 */
    public Object uncountedShifts(Long organizationId, Long allocationId, String yearMonth) {
        throw new UnsupportedOperationException("CMP-260909-2152 未実装: 締め後に未計上の勤務");
    }
}
