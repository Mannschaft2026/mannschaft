package com.mannschaft.app.shiftbudget.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.function.Function;

/**
 * 組織の締め日から締め期間を算出する純粋関数（CMP-260909-2152 確定設計 r12 §2）。
 *
 * <ul>
 *   <li>closingDate(M, d) = d=0 ? M の末日 : M.atDay(d)</li>
 *   <li>Period(M) = [closingDate(M-1, d(M-1)) + 1, closingDate(M, d(M))]（連続・非重複）</li>
 *   <li>期間キー＝締め日が属する月</li>
 *   <li>仕訳日 ＝ min(締め日, 割当終了日)</li>
 * </ul>
 *
 * <p>{@code closingDayOf} は期間キー M に有効な締め日（0〜28、0＝末日）を返す関数
 * （effective_period_key &lt;= M の変更のうち revision 最大、無ければ0）。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する。</p>
 */
public final class ShiftBudgetClosePeriodCalculator {

    private ShiftBudgetClosePeriodCalculator() {
    }

    /** 期間キー M・締め日 d の締め日（d=0 は末日）。 */
    public static LocalDate closingDate(YearMonth periodKey, int closingDay) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 期間キー M の締め期間。 */
    public static ClosePeriod period(YearMonth periodKey, Function<YearMonth, Integer> closingDayOf) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 勤務日（深夜跨ぎは開始日）が属する期間キー。 */
    public static YearMonth periodKeyOf(LocalDate workDate, Function<YearMonth, Integer> closingDayOf) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /** 仕訳日 ＝ min(締め日, 割当終了日)。 */
    public static LocalDate journalDate(ClosePeriod period, LocalDate allocationEnd) {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }

    /**
     * 締め期間。
     *
     * @param periodKey  期間キー（締め日が属する月）
     * @param start      期間開始日（含む）
     * @param end        期間終了日＝締め日（含む）
     * @param closingDay この期間の締め日（0＝末日）
     */
    public record ClosePeriod(YearMonth periodKey, LocalDate start, LocalDate end, int closingDay) {
    }
}
