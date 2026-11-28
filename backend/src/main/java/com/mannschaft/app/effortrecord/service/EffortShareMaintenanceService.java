package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortShareState;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareQueryRepository;
import com.mannschaft.app.membership.domain.EffortShareDisposition;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.timeline.service.TimelineEffortSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 共有の保守コマンド（データ設計 §8）。
 *
 * <p>イベント（{@code TeamEffortMembershipRevokedEvent}）が欠落しても、終了済み加入期間の REVOKED disposition と
 * 共有を grant_membership_id で突き合わせ、team・owner・timeline を冪等に失効させる。team 行の REVOKED を commit した後に
 * owner・timeline の後処理が欠けた行（team 行は REVOKED・owner 行は同じ共有と加入期間のまま未失効、または timeline の
 * 対応行が未失効）も拾い、後処理だけを再実行して収束させる。
 * 再送は保存加入期間 ID で対象を限定し、同じ user/team の別の加入期間（再加入）には触れない。
 * 共有行を id 順に {@value #CHUNK} 件ずつ読み、owner 行・timeline・資格の照会もチャンクごとに各 1 回でまとめて行う
 * （行ごとに照会しない）。非 TX で、失効は 1 件ずつ team・owner・timeline の各 TX で確定する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortShareMaintenanceService {

    /** 1 回に読む共有行の件数（資格 batch の上限 500 以下）。 */
    static final int CHUNK = 500;

    /** 加入期間の終了で owner 行を REVOKED にできる状態（{@code EffortShareTxService#revokeForEndedMembership} と同じ）。 */
    private static final Set<String> OWNER_REVOCABLE = Set.of(EffortShareState.PENDING.name(),
            EffortShareState.VISIBLE.name(), EffortShareState.FAILED.name());

    private final EffortTeamShareQueryRepository queries;
    private final MembershipService membershipService;
    private final EffortShareCoordinator coordinator;
    private final TimelineEffortSourceService timeline;

    /**
     * REVOKED で終わった加入期間に束縛されたまま失効・後処理が済んでいない共有を失効させる。
     *
     * @return team・owner・timeline のいずれかを失効させた共有の件数
     */
    public int reconcileRevokedMemberships() {
        int revoked = 0;
        UUID after = null;
        while (true) {
            List<EffortTeamShareEntity> rows = queries.findReconcilableAfter(after, CHUNK);
            if (rows.isEmpty()) {
                break;
            }
            List<EffortTeamShareEntity> targets = withPendingWork(rows);
            if (!targets.isEmpty()) {
                List<EffortShareQualificationQuery> batch = new ArrayList<>(targets.size());
                targets.forEach(r -> batch.add(new EffortShareQualificationQuery(r.getTeamId(), r.getOwnerUserId(),
                        r.getGrantMembershipId())));
                Map<Long, EffortShareQualification> byGrant = new HashMap<>();
                membershipService.getEffortShareQualificationsBatch(batch)
                        .forEach(q -> byGrant.put(q.grantMembershipId(), q));
                for (EffortTeamShareEntity row : targets) {
                    EffortShareQualification q = byGrant.get(row.getGrantMembershipId());
                    boolean endedExplicitly = q != null && Objects.equals(q.teamId(), row.getTeamId())
                            && Objects.equals(q.userId(), row.getOwnerUserId())
                            && !q.isCurrentEligible() && q.disposition() == EffortShareDisposition.REVOKED;
                    if (endedExplicitly && coordinator.revokeIfBound(row.getId(), row.getGrantMembershipId())) {
                        revoked++;
                    }
                }
            }
            if (rows.size() < CHUNK) {
                break;
            }
            after = rows.get(rows.size() - 1).getId();
        }
        log.info("加入期間の終了で失効すべき共有を補正: count={}", revoked);
        return revoked;
    }

    /**
     * 未失効（VISIBLE・PENDING）の行と、REVOKED のうち後処理が残っている行だけを返す。後処理の残りは、owner 行が同じ共有・
     * 同じ加入期間のまま未失効か、timeline の対応行が未失効か（各 1 回の一括照会。件数に比例させない）。
     */
    private List<EffortTeamShareEntity> withPendingWork(List<EffortTeamShareEntity> rows) {
        List<EffortTeamShareEntity> revokedRows = rows.stream()
                .filter(r -> r.getState() == EffortTeamShareState.REVOKED)
                .toList();
        Set<UUID> pending = new HashSet<>();
        if (!revokedRows.isEmpty()) {
            Map<UUID, EffortTeamShareQueryRepository.OwnerShareState> owners = new HashMap<>();
            queries.findOwnerShareStates(revokedRows.stream().map(EffortTeamShareEntity::getSourceRecordId).toList())
                    .forEach(o -> owners.put(o.recordId(), o));
            Set<UUID> unrevokedOnTimeline = timeline.findUnrevokedShareUuids(
                    revokedRows.stream().map(EffortTeamShareEntity::getId).toList());
            for (EffortTeamShareEntity row : revokedRows) {
                EffortTeamShareQueryRepository.OwnerShareState owner = owners.get(row.getSourceRecordId());
                boolean ownerPending = owner != null && row.getId().equals(owner.shareId())
                        && row.getGrantMembershipId().equals(owner.grantMembershipId())
                        && OWNER_REVOCABLE.contains(owner.shareState());
                if (ownerPending || unrevokedOnTimeline.contains(row.getId())) {
                    pending.add(row.getId());
                }
            }
        }
        return rows.stream()
                .filter(r -> r.getState() != EffortTeamShareState.REVOKED || pending.contains(r.getId()))
                .toList();
    }
}
