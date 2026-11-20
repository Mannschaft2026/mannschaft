package com.mannschaft.app.shift.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * シフト公開の予算連動判定に使う、公開 TX の外で固定した値（CMP-261115-1821）。
 *
 * <p>認可の後・公開 TX の前（非 tx の {@link ShiftScheduleFacade}）で、公開対象スケジュールの枠日付の閉区間
 * {@code [coveredFrom, coveredTo]} に重なる全予算割当候補と、候補組織ごとの予算機能の三値判定結果を取得し、
 * 本 record に固定する。公開 TX はこの値だけで予算連動を判定し、TX の中から shiftbudget・budget・feature の
 * Repository へ到達しない（D-3T の新規越境を作らない。設計書「Root採用・軍議検分反映」）。</p>
 *
 * <p>公開 TX は親スケジュールを FOR UPDATE した後に枠を読み直す。読み直した枠日付が本 context の範囲外、または
 * 事前に枠が無かった（{@link #noSlots()}）のに枠が現れていれば、判定の前提が崩れているので公開せず 409 で
 * 再操作を促す（{@link ShiftPublicationHourlyRateVerifier}）。</p>
 *
 * @param coveredFrom                  候補を取得した閉区間の開始日（事前に枠が無ければ null）
 * @param coveredTo                    候補を取得した閉区間の終了日（事前に枠が無ければ null）
 * @param candidates                   区間に重なる予算割当候補（チームが ACTIVE 加盟している組織のもの）
 * @param featureEnabledByOrganization 候補組織ごとの予算機能の有効判定（三値論理の評価済み）
 */
public record ShiftPublicationBudgetContext(
        LocalDate coveredFrom,
        LocalDate coveredTo,
        List<AllocationCandidate> candidates,
        Map<Long, Boolean> featureEnabledByOrganization) {

    /**
     * 予算割当の候補 1 件（値のみ。Entity は持ち込まない）。
     *
     * @param organizationId 計上先候補の組織ID
     * @param teamScoped     チーム個別の割当なら true、組織全体割当（team_id IS NULL）なら false
     * @param periodStart    割当期間の開始日
     * @param periodEnd      割当期間の終了日
     */
    public record AllocationCandidate(Long organizationId, boolean teamScoped,
                                      LocalDate periodStart, LocalDate periodEnd) {

        public AllocationCandidate {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
        }

        boolean contains(LocalDate date) {
            return !date.isBefore(periodStart) && !date.isAfter(periodEnd);
        }
    }

    public ShiftPublicationBudgetContext {
        if ((coveredFrom == null) != (coveredTo == null)) {
            throw new IllegalArgumentException("coveredFrom と coveredTo は両方指定するか両方 null にする");
        }
        if (coveredFrom != null && coveredFrom.isAfter(coveredTo)) {
            throw new IllegalArgumentException("coveredFrom が coveredTo より後: " + coveredFrom + " > " + coveredTo);
        }
        candidates = List.copyOf(candidates);
        featureEnabledByOrganization = Map.copyOf(featureEnabledByOrganization);
    }

    /** 事前に枠が 1 件も無かったことを表す context。 */
    public static ShiftPublicationBudgetContext noSlots() {
        return new ShiftPublicationBudgetContext(null, null, List.of(), Map.of());
    }

    /** 事前に枠が無く、候補を 1 件も取得していない context か。 */
    public boolean coversNoDates() {
        return coveredFrom == null;
    }

    /** 指定日が候補取得の閉区間に含まれるか。 */
    public boolean covers(LocalDate date) {
        return coveredFrom != null && !date.isBefore(coveredFrom) && !date.isAfter(coveredTo);
    }

    /**
     * 指定日が予算に連動するかを判定する（計上リスナーと同じ候補規則）。
     *
     * <p>チーム個別の割当を持つ組織があればそれを、無ければ組織全体割当を持つ組織を候補とする。
     * 候補のいずれかで予算機能が有効なら連動とみなす。候補が複数で計上先が一意に決まらない場合も
     * 「予算なし」として検証をすり抜けさせない（設計書 第3案 公開側 3）。</p>
     *
     * @param date 勤務日（{@link #covers(LocalDate)} が true であること）
     * @return 予算連動なら true
     */
    public boolean isBudgetLinked(LocalDate date) {
        if (!covers(date)) {
            throw new IllegalArgumentException("context の範囲外の日付: " + date);
        }
        Set<Long> teamScoped = organizationsContaining(date, true);
        Set<Long> organizations = teamScoped.isEmpty() ? organizationsContaining(date, false) : teamScoped;
        return organizations.stream().anyMatch(org -> Boolean.TRUE.equals(featureEnabledByOrganization.get(org)));
    }

    private Set<Long> organizationsContaining(LocalDate date, boolean teamScoped) {
        return candidates.stream()
                .filter(c -> c.teamScoped() == teamScoped && c.contains(date))
                .map(AllocationCandidate::organizationId)
                .collect(Collectors.toSet());
    }
}
