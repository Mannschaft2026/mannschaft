package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.shiftbudget.UuidBytes;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosePeriodEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosingDayChangeEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetClosePeriodRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetClosingDayChangeRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetClosingSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 組織の締め期間の解決（期間解決器。CMP-260909-2152 確定設計 r12 §2）。
 *
 * <p>期間キー M の期間は、確定済みの期間行があればその値（境界は固定）、無ければ締め日履歴から算出する
 * （{@link ShiftBudgetClosePeriodCalculator}）。仕訳日から期間を引くときは月キーではなく固定済み期間の範囲で引く。
 * 履歴・期間行は組織単位で1回の読取で取り、期間数に比例して文を増やさない（N+1 なし）。</p>
 *
 * <p>期間行の確定（{@link #ensureFixed}）は締め処理の前に独立 TX で行う。先頭行を {@code FOR UPDATE} で取って締め日変更と直列化し、
 * 以後の失敗でも期間行は残る（安全側）。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftBudgetClosePeriodResolver {

    private final ShiftBudgetClosingSettingRepository settingRepository;
    private final ShiftBudgetClosingDayChangeRepository changeRepository;
    private final ShiftBudgetClosePeriodRepository periodRepository;

    /**
     * 解決した締め期間。
     *
     * @param periodKey  期間キー
     * @param start      期間開始日
     * @param end        期間終了日（締め日）
     * @param closingDay 締め日（0＝末日）
     * @param fixed      期間行として確定済みか
     */
    public record ResolvedPeriod(YearMonth periodKey, LocalDate start, LocalDate end, int closingDay, boolean fixed) {
    }

    /** 組織の締め日履歴から、期間キー M に有効な締め日を返す関数（effective_period_key &lt;= M のうち revision 最大、無ければ0）。 */
    @Transactional(readOnly = true)
    public Function<YearMonth, Integer> closingDayFunction(Long organizationId) {
        return closingDayFunction(changeRepository.findByOrganizationIdOrderByRevisionAsc(organizationId));
    }

    static Function<YearMonth, Integer> closingDayFunction(List<ShiftBudgetClosingDayChangeEntity> changes) {
        List<Change> snapshot = new ArrayList<>(changes.size());
        for (ShiftBudgetClosingDayChangeEntity c : changes) {
            snapshot.add(new Change(YearMonth.parse(c.getEffectivePeriodKey()), c.getRevision(), c.getClosingDay()));
        }
        return key -> {
            Change best = null;
            for (Change c : snapshot) {
                if (!c.effective().isAfter(key) && (best == null || c.revision() > best.revision())) {
                    best = c;
                }
            }
            return best == null ? 0 : best.closingDay();
        };
    }

    private record Change(YearMonth effective, int revision, int closingDay) {
    }

    /** 期間キー M の締め期間（確定済みならその値）。 */
    @Transactional(readOnly = true)
    public ResolvedPeriod resolve(Long organizationId, YearMonth periodKey) {
        return periodRepository.findByOrganizationIdAndPeriodKey(organizationId, periodKey.toString())
                .map(ShiftBudgetClosePeriodResolver::fromRow)
                .orElseGet(() -> compute(periodKey, closingDayFunction(organizationId)));
    }

    /** 期間キーの範囲 [from, to] の期間一覧（確定済みはその値、未確定は算出）。履歴と期間行を1回ずつ読む。 */
    @Transactional(readOnly = true)
    public List<ResolvedPeriod> list(Long organizationId, YearMonth from, YearMonth to) {
        Function<YearMonth, Integer> fn = closingDayFunction(organizationId);
        Map<String, ShiftBudgetClosePeriodEntity> fixed = periodRepository
                .findByOrganizationIdAndPeriodKeyBetweenOrderByPeriodKeyAsc(organizationId, from.toString(), to.toString())
                .stream().collect(Collectors.toMap(ShiftBudgetClosePeriodEntity::getPeriodKey, p -> p));
        List<ResolvedPeriod> out = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            ShiftBudgetClosePeriodEntity row = fixed.get(m.toString());
            out.add(row != null ? fromRow(row) : compute(m, fn));
        }
        return out;
    }

    /** 組織の確定済み期間の最大期間キー（旧締めの取り込み行を含む）。無ければ null。 */
    @Transactional(readOnly = true)
    public YearMonth maxFixedKey(Long organizationId) {
        String key = periodRepository.findMaxPeriodKey(organizationId);
        return key == null ? null : YearMonth.parse(key);
    }

    /**
     * 期間行を確定する（無ければ作る）。独立 TX: 先頭行を {@code FOR UPDATE}（INSERT IGNORE の後）で取り、既存を読み、無ければ INSERT する。
     * 以後の失敗でも期間行は残る。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResolvedPeriod ensureFixed(Long organizationId, YearMonth periodKey) {
        settingRepository.insertIgnore(UuidBytes.toBytes(UuidV7.generate()), organizationId);
        settingRepository.findByOrganizationIdForUpdate(organizationId);
        var existing = periodRepository.findByOrganizationIdAndPeriodKey(organizationId, periodKey.toString());
        if (existing.isPresent()) {
            return fromRow(existing.get());
        }
        ResolvedPeriod computed = compute(periodKey, closingDayFunction(organizationId));
        ShiftBudgetClosePeriodEntity saved = periodRepository.saveAndFlush(ShiftBudgetClosePeriodEntity.builder()
                .organizationId(organizationId)
                .periodKey(periodKey.toString())
                .periodStart(computed.start())
                .periodEnd(computed.end())
                .closingDay(computed.closingDay())
                .build());
        return fromRow(saved);
    }

    private static ResolvedPeriod compute(YearMonth key, Function<YearMonth, Integer> fn) {
        ShiftBudgetClosePeriodCalculator.ClosePeriod p = ShiftBudgetClosePeriodCalculator.period(key, fn);
        return new ResolvedPeriod(p.periodKey(), p.start(), p.end(), p.closingDay(), false);
    }

    private static ResolvedPeriod fromRow(ShiftBudgetClosePeriodEntity row) {
        return new ResolvedPeriod(YearMonth.parse(row.getPeriodKey()), row.getPeriodStart(), row.getPeriodEnd(),
                row.getClosingDay(), true);
    }
}
