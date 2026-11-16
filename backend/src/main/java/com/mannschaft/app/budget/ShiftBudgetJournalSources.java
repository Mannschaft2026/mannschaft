package com.mannschaft.app.budget;

/**
 * CMP-260909-2152 シフト予算が自動計上する仕訳の {@code source_type}。
 *
 * <p>月次締めの原仕訳と、締め後訂正の差額仕訳は、汎用の取引削除・取消（反転仕訳）で触らせない。
 * 金額の正本は訂正履歴（budget_monthly_close_corrections）であり、汎用取消の「当日付の逆種別」仕訳を作ると
 * 月次の集計が二重に動くため、{@link com.mannschaft.app.budget.service.BudgetTransactionService} が 409 で拒否し、
 * DB トリガー（V261）が SQL からの削除・更新も拒否する。</p>
 */
public final class ShiftBudgetJournalSources {

    /** 月次締めの原仕訳。 */
    public static final String MONTHLY = "SHIFT_BUDGET_MONTHLY";

    /** 締め後訂正の差額仕訳（符号付き EXPENSE）。 */
    public static final String CORRECTION = "SHIFT_BUDGET_CORRECTION";

    private ShiftBudgetJournalSources() {
    }

    /** 汎用の削除・取消から保護する仕訳か。 */
    public static boolean isProtected(String sourceType) {
        return MONTHLY.equals(sourceType) || CORRECTION.equals(sourceType);
    }
}
