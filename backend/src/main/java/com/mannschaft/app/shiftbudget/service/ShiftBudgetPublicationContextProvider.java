package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shift.service.ShiftPublicationBudgetContext;
import com.mannschaft.app.shift.service.ShiftPublicationBudgetContextPort;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * シフト公開の予算連動判定に使う値を、公開 TX の外で取得して固定する（CMP-261115-1821）。
 *
 * <p>{@code ShiftScheduleFacade}（非 tx・認可の後）から {@link ShiftPublicationBudgetContextPort} 経由で呼ばれる。
 * 本クラスは {@code @Transactional} を付けない（公開 TX へ参加しない）。予算割当候補は期間で 1 回、
 * 予算機能の三値判定は候補組織ごとに 1 回だけ引き、不変の {@link ShiftPublicationBudgetContext} として返す。
 * 公開 TX はこの値だけで連動を判定し、shiftbudget・budget の Repository へ到達しない（D-3T の新規越境なし）。</p>
 *
 * <p>候補の規則は計上リスナー（{@code ShiftBudgetConsumptionRecordListener#resolveAllocationForSlot}）と同じ
 * 「ACTIVE 加盟の組織・チーム個別優先・無ければ組織全体」。所有モデルは変えない。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetPublicationContextProvider implements ShiftPublicationBudgetContextPort {

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetFeatureService featureService;

    @Override
    public ShiftPublicationBudgetContext load(Long teamId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        List<ShiftPublicationBudgetContext.AllocationCandidate> candidates = new ArrayList<>();
        for (Object[] row : allocationRepository.findPublicationCandidatesOverlapping(teamId, from, to)) {
            candidates.add(new ShiftPublicationBudgetContext.AllocationCandidate(
                    ((Number) row[0]).longValue(),
                    row[1] != null,
                    toLocalDate(row[2]),
                    toLocalDate(row[3])));
        }
        Map<Long, Boolean> featureEnabledByOrganization = new LinkedHashMap<>();
        for (ShiftPublicationBudgetContext.AllocationCandidate candidate : candidates) {
            featureEnabledByOrganization.computeIfAbsent(candidate.organizationId(), featureService::isEnabled);
        }
        return new ShiftPublicationBudgetContext(from, to, candidates, featureEnabledByOrganization);
    }

    /** ネイティブのスカラー投影の DATE は JDBC 既定型で戻りうるため LocalDate へ揃える。 */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        throw new IllegalStateException("予算割当の期間を日付として読めません: "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
