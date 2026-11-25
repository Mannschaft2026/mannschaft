package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortShareState;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareQueryRepository;
import com.mannschaft.app.membership.domain.EffortShareDisposition;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.moderation.service.ContentHiddenStatusService;
import com.mannschaft.app.timeline.service.TimelineEffortSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 チーム共有の F00 判定（データ設計 §3・§8。一覧・詳細・タイムライン・定義候補で同じ判定を使う）。
 *
 * <p>共有 1 件が見えるのは次をすべて満たすときだけ:</p>
 * <ol>
 *   <li>閲覧者がそのチームに現在所属している（SYSTEM_ADMIN でも所属がなければ不可）。</li>
 *   <li>チームが削除されていない（親組織の削除・離脱は判定に使わない）。</li>
 *   <li>team 行が VISIBLE で、owner 行（purge 後の tombstone を含む。本人の削除は deny）も VISIBLE で、
 *       share UUID・チーム・shareVersion・contentRevision（同期済み版）・加入期間 ID・hash がすべて一致する。</li>
 *   <li>投稿者の加入期間 ID が現在有効な直接所属である（退会整理だけで終わった RETAINED_ACCOUNT_WITHDRAWAL は read だけ許す）。</li>
 *   <li>対応する努力由来のタイムライン投稿が未失効・公開中で、通報・モデレーションで非表示にされていない（データ設計 §7.2）。
 *       非表示の確定通知（AFTER_COMMIT で team 行を MODERATED にする後処理）が欠けても、投稿の非表示・削除状態を
 *       ここで読むため共有は見えない。</li>
 * </ol>
 *
 * <p>owner 照合・投稿状態の照会（timeline・moderation の Service 経由で各 1 回）・資格照会は 1 ページにつき定数回
 * （件数に比例させない）。照会の障害は {@code EFFORT_004}（503）として
 * 呼出元へ伝え、空の一覧や「見えない」に置き換えない（障害を許可にも倒さない）。キャッシュは使わない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffortTeamShareVisibility {

    private static final String TEAM = "TEAM";
    /** 資格 batch 1 回あたりの上限（MembershipService の契約）。 */
    private static final int QUALIFICATION_CHUNK = 500;

    private final EffortTeamShareQueryRepository queries;
    private final MembershipService membershipService;
    private final AccessControlService accessControl;
    /** チームの有効性（削除済みは返らない）を一括で引く。TeamService は F00 の Checker と循環するため使わない。 */
    private final NameResolverService names;
    private final EffortShareMetrics metrics;
    /** 努力由来投稿の対応行・投稿の公開状態（timeline ドメイン。Service 経由・ID 参照）。 */
    private final TimelineEffortSourceService timelineSources;
    /** 通報・モデレーションによる投稿の非表示（moderation ドメイン。Service 経由・ID 参照）。 */
    private final ContentHiddenStatusService contentHidden;

    /**
     * 判定結果。
     *
     * @param allowed     見える share UUID
     * @param purgedOwner 見える共有のうち投稿者が purge 済み（著者を匿名で表示する）share UUID
     */
    public record Result(Set<UUID> allowed, Set<UUID> purgedOwner) {
    }

    /** share UUID の集合を判定する（タイムライン・単件判定の入口）。team 行は 1 回で読む。 */
    public Result filterByShareIds(Collection<UUID> shareIds, Long viewerUserId) {
        if (shareIds == null || shareIds.isEmpty() || viewerUserId == null) {
            return new Result(Set.of(), Set.of());
        }
        List<EffortTeamShareEntity> rows;
        try {
            rows = queries.findByIds(new LinkedHashSet<>(shareIds));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        return filter(rows, viewerUserId, null);
    }

    /**
     * 読み込み済みの team 行を判定する。viewerVerifiedTeamId が非 null なら、そのチームの存在と閲覧者の所属は
     * 呼出元（チーム一覧・詳細）が直前に確認済みとして再照会しない。
     */
    public Result filter(List<EffortTeamShareEntity> rows, Long viewerUserId, Long viewerVerifiedTeamId) {
        if (rows.isEmpty() || viewerUserId == null) {
            return new Result(Set.of(), Set.of());
        }
        try {
            Set<Long> readableTeams = readableTeams(rows, viewerUserId, viewerVerifiedTeamId);
            List<EffortTeamShareEntity> candidates = new ArrayList<>();
            for (EffortTeamShareEntity row : rows) {
                if (row.getState() != EffortTeamShareState.VISIBLE) {
                    metrics.denied("team_state");
                } else if (!readableTeams.contains(row.getTeamId())) {
                    metrics.denied("viewer_scope");
                } else {
                    candidates.add(row);
                }
            }
            if (candidates.isEmpty()) {
                return new Result(Set.of(), Set.of());
            }
            Map<UUID, EffortTeamShareQueryRepository.OwnerShareState> owners = new HashMap<>();
            queries.findOwnerShareStates(candidates.stream().map(EffortTeamShareEntity::getSourceRecordId).toList())
                    .forEach(o -> owners.put(o.recordId(), o));
            List<EffortTeamShareEntity> synced = new ArrayList<>();
            for (EffortTeamShareEntity row : candidates) {
                if (isSynced(row, owners.get(row.getSourceRecordId()))) {
                    synced.add(row);
                } else {
                    metrics.denied("owner_state");
                }
            }
            Set<UUID> postVisible = visiblePosts(synced);
            List<EffortTeamShareEntity> posted = new ArrayList<>();
            for (EffortTeamShareEntity row : synced) {
                if (postVisible.contains(row.getId())) {
                    posted.add(row);
                } else {
                    metrics.denied("post_state");
                }
            }
            Set<UUID> allowed = new HashSet<>();
            Set<UUID> purged = new HashSet<>();
            Map<Long, EffortShareQualification> qualifications = qualify(posted);
            for (EffortTeamShareEntity row : posted) {
                EffortShareQualification q = qualifications.get(row.getGrantMembershipId());
                boolean qualified = q != null && Objects.equals(q.teamId(), row.getTeamId())
                        && Objects.equals(q.userId(), row.getOwnerUserId())
                        && (q.isCurrentEligible() || q.disposition() == EffortShareDisposition.RETAINED_ACCOUNT_WITHDRAWAL);
                if (!qualified) {
                    metrics.denied("membership");
                    continue;
                }
                allowed.add(row.getId());
                if (owners.get(row.getSourceRecordId()).purged()) {
                    purged.add(row.getId());
                }
            }
            return new Result(allowed, purged);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** owner 行が同じ共有を VISIBLE で確定し、team 行と版・加入期間 ID・hash が一致しているか。 */
    private static boolean isSynced(EffortTeamShareEntity row, EffortTeamShareQueryRepository.OwnerShareState owner) {
        return owner != null && !owner.deleted()
                && EffortShareState.VISIBLE.name().equals(owner.shareState())
                && row.getId().equals(owner.shareId())
                && row.getTeamId().equals(owner.shareTeamId())
                && row.getOwnerUserId().equals(owner.ownerUserId())
                && row.getShareVersion() == owner.shareVersion()
                && row.getSourceVersion() == owner.contentRevision()
                && Objects.equals(owner.syncedSourceVersion(), owner.contentRevision())
                && row.getGrantMembershipId().equals(owner.grantMembershipId())
                && row.getProjectionHash().equals(owner.desiredHash());
    }

    /**
     * 対応する努力由来投稿が未失効・公開中で、通報・モデレーションで非表示にされていない共有（データ設計 §7.2）。
     * timeline の対応表と moderation の非表示状態をそれぞれ 1 回で引く（件数に比例させない）。対応投稿の無い共有は
     * 同期が完了していない（team → timeline → owner ack の順に確定する）ため見せない。
     */
    private Set<UUID> visiblePosts(List<EffortTeamShareEntity> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        Map<UUID, Long> livePosts = timelineSources.findLivePostIdsByShareUuids(
                rows.stream().map(EffortTeamShareEntity::getId).toList());
        if (livePosts.isEmpty()) {
            return Set.of();
        }
        Set<Long> hidden = contentHidden.findHiddenTimelinePostIds(livePosts.values());
        Set<UUID> visible = new HashSet<>();
        livePosts.forEach((shareId, postId) -> {
            if (!hidden.contains(postId)) {
                visible.add(shareId);
            }
        });
        return visible;
    }

    /** 閲覧者が現在所属し、削除されていないチーム。 */
    private Set<Long> readableTeams(List<EffortTeamShareEntity> rows, Long viewerUserId, Long viewerVerifiedTeamId) {
        Set<Long> teams = new LinkedHashSet<>();
        rows.forEach(r -> teams.add(r.getTeamId()));
        Set<Long> readable = new HashSet<>();
        List<AccessControlService.TeamUserKey> keys = new ArrayList<>();
        for (Long teamId : teams) {
            if (teamId.equals(viewerVerifiedTeamId)) {
                readable.add(teamId);
            } else {
                keys.add(new AccessControlService.TeamUserKey(teamId, viewerUserId));
            }
        }
        if (keys.isEmpty()) {
            return readable;
        }
        Map<AccessControlService.TeamUserKey, AccessControlService.TeamMembershipState> states =
                accessControl.getTeamMembershipStates(keys);
        // チーム削除は所属行が残っていても即 deny（論理削除済みのチームは名前の一括解決に返らない）
        Set<Long> liveTeams = names.resolveTeamNames(keys.stream().map(AccessControlService.TeamUserKey::teamId).toList())
                .keySet();
        for (AccessControlService.TeamUserKey key : keys) {
            AccessControlService.TeamMembershipState state = states.get(key);
            if (state != null && state.active() && liveTeams.contains(key.teamId())) {
                readable.add(key.teamId());
            }
        }
        return readable;
    }

    /** 加入期間 ID ごとの資格（PRIMARY の独立 TX で 500 件ずつ。ページ単位では 1 回）。 */
    private Map<Long, EffortShareQualification> qualify(List<EffortTeamShareEntity> rows) {
        Map<Long, EffortShareQualification> result = new HashMap<>();
        List<EffortShareQualificationQuery> queriesForBatch = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (EffortTeamShareEntity row : rows) {
            if (seen.add(row.getGrantMembershipId())) {
                queriesForBatch.add(new EffortShareQualificationQuery(row.getTeamId(), row.getOwnerUserId(),
                        row.getGrantMembershipId()));
            }
        }
        for (int from = 0; from < queriesForBatch.size(); from += QUALIFICATION_CHUNK) {
            List<EffortShareQualificationQuery> chunk =
                    queriesForBatch.subList(from, Math.min(queriesForBatch.size(), from + QUALIFICATION_CHUNK));
            membershipService.getEffortShareQualificationsBatch(chunk)
                    .forEach(q -> result.put(q.grantMembershipId(), q));
        }
        return result;
    }

    private BusinessException unavailable(DataAccessException e) {
        metrics.denied("lookup_failure");
        // 照会の障害は値を含まない例外種別だけを記録する（SQL・値・token をログへ出さない）。
        log.error("頑張り記録の共有の可視性照会に失敗: cause={}", e.getClass().getSimpleName());
        return new BusinessException(EffortErrorCode.EFFORT_004);
    }
}
