package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;

/**
 * CMP-260909-2152 月次締めイベントの追記口（Spring Data のカスタム断片）。
 *
 * <p>締め TX を持つ {@code MonthlyShiftBudgetCloseService} は、割当リポジトリ（既存の依存）だけで
 * 自ドメインの締めイベントを同一 TX で INSERT できる。締めサービスのコンストラクタ引数を増やさないのは、
 * 同クラスの他ドメイン Repository 依存（ArchUnit 凍結ストア登録済みの既存負債）の凍結キーが
 * コンストラクタのシグネチャで決まり、引数を足すと新しい違反として数えられるため。</p>
 */
public interface ShiftBudgetCloseEventRecorder {

    /** 呼出側の TX の中で締めイベントを INSERT する（TX が無ければ失敗する）。 */
    ShiftBudgetMonthlyCloseEventEntity recordClose(ShiftBudgetMonthlyCloseEventEntity event);
}
