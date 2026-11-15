package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * シフト公開前の勤務日時給ガード（CMP-261115-1821）。
 *
 * <p>公開後の予算計上は AFTER_COMMIT の非同期リスナーが行い、時給のない割当者は黙って計上から外れる
 * （予算管理が無効なのに公開は成功する）。そのため予算に連動する勤務日については、公開の同一TX内で
 * 「割当者の勤務日時点の有効時給が正数で存在するか」を検証し、欠落を公開前に報告する。</p>
 *
 * <p>予算連動の判定は計上リスナーと同じ規則（チーム割当を優先し、無ければ組織全体割当。いずれも
 * 組織が一意に決まり、その組織の予算機能が有効であること）に揃える。時給は勤務日ごとに
 * 全割当者を 1 クエリで引く（N+1 を避ける）。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftPublicationHourlyRateGuard {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftHourlyRateRepository hourlyRateRepository;

    /** 時給が確認できなかった（割当者, 勤務日）。 */
    public record MissingRate(Long userId, LocalDate slotDate) {
    }

    /**
     * 予算に連動する勤務日の割当者のうち、有効な正数時給を持たない組を返す。
     *
     * @param teamId         公開対象スケジュールのチームID
     * @param assignedByDate 勤務日 → その日に割り当てられた全ユーザーID
     * @return 欠落した（ユーザー, 勤務日）。日付→ユーザーIDの昇順。欠落がなければ空
     */
    public List<MissingRate> findMissing(Long teamId, Map<LocalDate, Set<Long>> assignedByDate) {
        List<MissingRate> missing = new ArrayList<>();
        Map<Long, Boolean> featureByOrg = new HashMap<>();
        for (Map.Entry<LocalDate, Set<Long>> entry : assignedByDate.entrySet()) {
            LocalDate date = entry.getKey();
            Set<Long> userIds = entry.getValue();
            if (userIds.isEmpty() || !isBudgetLinked(teamId, date, featureByOrg)) {
                continue;
            }
            Set<Long> rated = new HashSet<>();
            hourlyRateRepository.findEffectiveRatesByTeam(teamId, date, List.copyOf(userIds)).stream()
                    .filter(r -> r.getHourlyRate() != null && r.getHourlyRate().signum() > 0)
                    .forEach(r -> rated.add(r.getUserId()));
            for (Long userId : userIds) {
                if (!rated.contains(userId)) {
                    missing.add(new MissingRate(userId, date));
                }
            }
        }
        missing.sort(Comparator.comparing(MissingRate::slotDate).thenComparing(MissingRate::userId));
        return missing;
    }

    private boolean isBudgetLinked(Long teamId, LocalDate date, Map<Long, Boolean> featureByOrg) {
        List<Long> orgIds = allocationRepository.findOrganizationIdsWithTeamAllocationContaining(teamId, date);
        if (orgIds.isEmpty()) {
            orgIds = allocationRepository.findOrganizationIdsWithOrgWideAllocationContaining(teamId, date);
        }
        // 計上先が一意に決まらない日は、リスナーも計上しないため時給の検証対象にしない。
        if (orgIds.size() != 1) {
            return false;
        }
        return featureByOrg.computeIfAbsent(orgIds.get(0), featureService::isEnabled);
    }
}
