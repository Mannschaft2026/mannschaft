package com.mannschaft.app.shiftbudget;

/**
 * 月次締め済みの月の勤務を、通常の消化記録として計上しようとしたことを表す（CMP-260909-2152）。
 *
 * <p>締めた月は再び締められないため、ここで PLANNED を作ると永久に未計上のまま残る。計上は
 * 月次締め後訂正 API の ADD（画面の「締め後に未計上の勤務」から1操作で追加）だけに一本化する。
 * 呼出元（シフト公開の hook）は {@link IllegalStateException} として捕まえ、その勤務だけをスキップする。</p>
 */
public class ShiftBudgetClosedMonthException extends IllegalStateException {

    public ShiftBudgetClosedMonthException(Long allocationId, String month, Long slotId, Long userId) {
        super("月次締め済みのため訂正 API の ADD で計上する: allocation=" + allocationId + ", month=" + month
                + ", slot=" + slotId + ", user=" + userId);
    }
}
