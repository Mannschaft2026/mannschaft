package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

/**
 * F08.7 シフト予算 月次締めの入口（CMP-261021-0657）。
 *
 * <p>月次締めは「月末を過ぎた月」（壁時計 JST 基準で前月以前）だけを対象とする。
 * 当月・未来月を締めると、締めた後に記録される消化が PLANNED のまま取り残され、
 * 確定額と月次仕訳が実態とずれるため、{@link ShiftBudgetErrorCode#MONTH_NOT_ENDED} (409) で拒否する。</p>
 *
 * <p>判定をここに置く理由: {@link MonthlyShiftBudgetCloseService} のコンストラクタ署名は
 * ArchUnit 凍結ストア（D-5 / D-3）のキーに含まれており、壁時計を足すと既存負債の再登録になる。
 * そのため壁時計を持つ本クラスを入口として切り出し、Controller と cron はここを経由させる。
 * 他の本番クラスが {@code MonthlyShiftBudgetCloseService} の締め処理を直接呼ぶことは
 * 番人 {@code MonthlyShiftBudgetCloseGateGuardArchTest} が禁止している。</p>
 */
@Service
@RequiredArgsConstructor
public class MonthlyShiftBudgetCloseGate {

    private final MonthlyShiftBudgetCloseService closeService;
    private final ShiftBudgetFeatureService featureService;

    /**
     * 「対象月が終わったか」を判定する壁時計。
     *
     * <p>月の境界は利用者の暦（JST）で判定する。JVM 既定ゾーンに依存すると、UTC 環境では
     * JST の月初 0:00〜9:00 に前月がまだ「当月」と判定される。</p>
     */
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * API #11 経由の手動締め。
     *
     * <p>判定順: 機能フラグ → {@code BUDGET_ADMIN}（027 / 403）→ 対象月（032 / 409）。
     * 権限の無い利用者には対象月の判定結果を見せない。</p>
     */
    public MonthlyShiftBudgetCloseService.CloseResult close(Long organizationId, YearMonth targetMonth) {
        featureService.requireEnabled(organizationId);
        closeService.requireBudgetAdmin(organizationId);
        requireMonthEnded(targetMonth);
        return closeService.close(organizationId, targetMonth);
    }

    /**
     * 全組織横断の締め（cron / バッチ手動再実行）。
     *
     * <p>対象月の判定は組織ループの前に 1 回だけ行う（未終了月で組織ごとに失敗イベントを溜めない）。</p>
     */
    public MonthlyShiftBudgetCloseService.AllOrgsCloseResult closeAll(YearMonth targetMonth) {
        requireMonthEnded(targetMonth);
        return closeService.closeAll(targetMonth);
    }

    /** 壁時計（JST）基準の前月。cron の締め対象月。 */
    public YearMonth previousMonth() {
        return YearMonth.now(wallClock).minusMonths(1);
    }

    private void requireMonthEnded(YearMonth targetMonth) {
        if (!targetMonth.isBefore(YearMonth.now(wallClock))) {
            throw new BusinessException(ShiftBudgetErrorCode.MONTH_NOT_ENDED,
                    List.of(new ErrorResponse.FieldError("year_month",
                            ShiftBudgetErrorCode.MONTH_NOT_ENDED.getMessage())));
        }
    }
}
