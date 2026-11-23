package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareQueryRepository;
import com.mannschaft.app.membership.domain.EffortShareDisposition;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.service.MembershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F06.4.1 共有の保守コマンド（データ設計 §8）。
 *
 * <p>イベント（{@code TeamEffortMembershipRevokedEvent}）が欠落しても、終了済み加入期間の REVOKED disposition と
 * 未失効の共有を grant_membership_id で突き合わせ、team・owner・timeline を冪等に失効させる。
 * 再送は保存加入期間 ID で対象を限定し、同じ user/team の別の加入期間（再加入）には触れない。
 * 未失効の共有行を id 順に {@value #CHUNK} 件ずつ読み、資格照会も同じ件数ずつまとめて行う（行ごとに照会しない）。
 * 非 TX で、失効は 1 件ずつ team・owner・timeline の各 TX で確定する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortShareMaintenanceService {

    /** 1 回に読む共有行の件数（資格 batch の上限 500 以下）。 */
    static final int CHUNK = 500;

    private final EffortTeamShareQueryRepository queries;
    private final MembershipService membershipService;
    private final EffortShareCoordinator coordinator;

    /**
     * REVOKED で終わった加入期間に束縛されたまま未失効の共有を失効させる。
     *
     * @return 失効させた共有の件数
     */
    public int reconcileRevokedMemberships() {
        int revoked = 0;
        UUID after = null;
        while (true) {
            List<EffortTeamShareEntity> rows = queries.findLiveAfter(after, CHUNK);
            if (rows.isEmpty()) {
                break;
            }
            List<EffortShareQualificationQuery> batch = new ArrayList<>(rows.size());
            rows.forEach(r -> batch.add(new EffortShareQualificationQuery(r.getTeamId(), r.getOwnerUserId(),
                    r.getGrantMembershipId())));
            Map<Long, EffortShareQualification> byGrant = new HashMap<>();
            membershipService.getEffortShareQualificationsBatch(batch).forEach(q -> byGrant.put(q.grantMembershipId(), q));
            for (EffortTeamShareEntity row : rows) {
                EffortShareQualification q = byGrant.get(row.getGrantMembershipId());
                boolean endedExplicitly = q != null && Objects.equals(q.teamId(), row.getTeamId())
                        && Objects.equals(q.userId(), row.getOwnerUserId())
                        && !q.isCurrentEligible() && q.disposition() == EffortShareDisposition.REVOKED;
                if (endedExplicitly && coordinator.revokeIfBound(row.getId(), row.getGrantMembershipId())) {
                    revoked++;
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
}
