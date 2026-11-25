package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelineEffortSourceEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelineEffortSourceRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F06.4.1 努力由来の TEAM タイムライン投稿の内部 Service（データ設計 §7。外部 Controller へ公開しない）。
 *
 * <p>effort の共有 coordinator（認可・版の照合を済ませた非 TX の呼出元）だけが呼ぶ。timeline 自身の TX で投稿と対応表を
 * 原子的に upsert / revoke し、timeline TX 内で effort の Repository へ到達しない。投稿本文には値を複写せず、
 * 固定の非個人識別子 {@link #EFFORT_POST_CONTENT} だけを入れる（値は読み取り時に F00 を通った共有から組み立てる）。
 * 新規投稿は TEAM 固定・share_with_friends=false・転送元なし・外部公開不可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineEffortSourceService {

    /** 努力由来投稿の本文（固定の非個人識別子）。 */
    public static final String EFFORT_POST_CONTENT = "effort.share";

    private final TimelineEffortSourceRepository sources;
    private final TimelinePostRepository posts;

    @PersistenceContext
    private EntityManager entityManager;

    /** upsert の結果。 */
    public enum UpsertResult {
        /** 投稿と対応表を確定した（同じ版の再送を含む）。 */
        APPLIED,
        /** 古い版・取消済みの旧版・加入期間の置換条件を満たさないため何もしなかった。 */
        STALE
    }

    /**
     * 共有 UUID を一意 source として投稿 1 件と対応行を upsert する。
     *
     * <p>同じ加入期間 ID なら shareVersion → contentRevision の順に単調で、取消済みの行は新しい本人許可（より大きい
     * shareVersion）でだけ復帰する。加入期間 ID の置換（再加入後の再共有）は shareVersion が大きいときだけ認める
     * （現在の資格との一致は呼出元 coordinator が確認済み）。投稿の createdAt は初回のまま変えない。</p>
     */
    @Transactional
    public UpsertResult upsertTeamShare(Long teamId, UUID shareUuid, long shareVersion, long contentRevision,
                                        Long grantMembershipId, Long authorUserId) {
        Optional<TimelineEffortSourceEntity> current = sources.findByShareUuidForUpdate(shareUuid);
        if (current.isEmpty()) {
            TimelinePostEntity post = posts.saveAndFlush(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.TEAM)
                    .scopeId(teamId)
                    .userId(authorUserId)
                    .content(EFFORT_POST_CONTENT)
                    .status(PostStatus.PUBLISHED)
                    .shareWithFriends(false)
                    .publicVisible(false)
                    .build());
            sources.saveAndFlush(TimelineEffortSourceEntity.builder()
                    .teamId(teamId)
                    .timelinePostId(post.getId())
                    .shareUuid(shareUuid)
                    .grantMembershipId(grantMembershipId)
                    .shareVersion(shareVersion)
                    .contentRevision(contentRevision)
                    .isRevoked(false)
                    .build());
            return UpsertResult.APPLIED;
        }
        TimelineEffortSourceEntity row = current.get();
        if (!row.getTeamId().equals(teamId)) {
            return UpsertResult.STALE;
        }
        boolean sameGrant = row.getGrantMembershipId().equals(grantMembershipId);
        boolean older = shareVersion < row.getShareVersion()
                || (shareVersion == row.getShareVersion() && contentRevision < row.getContentRevision());
        boolean revivesOld = Boolean.TRUE.equals(row.getIsRevoked()) && shareVersion <= row.getShareVersion();
        if ((sameGrant && (older || revivesOld)) || (!sameGrant && shareVersion <= row.getShareVersion())) {
            return UpsertResult.STALE;
        }
        boolean wasRevoked = Boolean.TRUE.equals(row.getIsRevoked());
        row.setGrantMembershipId(grantMembershipId);
        row.setShareVersion(shareVersion);
        row.setContentRevision(contentRevision);
        row.setIsRevoked(false);
        sources.saveAndFlush(row);
        if (wasRevoked) {
            // 取消で論理削除した同じ投稿を戻す（再共有でも投稿を増やさない）。@SQLRestriction を通らない native 更新。
            entityManager.createNativeQuery("UPDATE timeline_posts SET deleted_at = NULL, status = 'PUBLISHED', "
                            + "updated_at = ?1 WHERE id = ?2")
                    .setParameter(1, LocalDateTime.now())
                    .setParameter(2, row.getTimelinePostId())
                    .executeUpdate();
        }
        return UpsertResult.APPLIED;
    }

    /**
     * 対応行の保存加入期間 ID が一致し、保存 shareVersion が期待値以下で未失効の行だけを失効させ、その更新に成功したときだけ
     * 対応投稿を論理削除する（不一致は何もしない。投稿を先に隠さない）。m2 で再共有済みの行へ m1 の失効が遅れて届いても
     * 加入期間 ID が一致しないため変更しない。
     *
     * @return 失効させたら true
     */
    @Transactional
    public boolean revokeTeamShare(Long teamId, UUID shareUuid, Long expectedGrantMembershipId, long expectedShareVersion) {
        Optional<TimelineEffortSourceEntity> current = sources.findByShareUuidForUpdate(shareUuid)
                .filter(row -> row.getTeamId().equals(teamId))
                .filter(row -> !Boolean.TRUE.equals(row.getIsRevoked()))
                .filter(row -> row.getGrantMembershipId().equals(expectedGrantMembershipId))
                .filter(row -> row.getShareVersion() <= expectedShareVersion);
        if (current.isEmpty()) {
            return false;
        }
        TimelineEffortSourceEntity row = current.get();
        row.setIsRevoked(true);
        sources.saveAndFlush(row);
        posts.findById(row.getTimelinePostId()).ifPresent(post -> {
            post.softDelete();
            posts.saveAndFlush(post);
        });
        return true;
    }

    /** 投稿が努力由来ならその share UUID（通報による非表示を effort の MODERATED へ寄せるため）。 */
    @Transactional(readOnly = true)
    public Optional<UUID> findShareUuidByPostId(Long postId) {
        return sources.findByTimelinePostId(postId).map(TimelineEffortSourceEntity::getShareUuid);
    }

    /**
     * share UUID の集合のうち、対応行が未失効で対応投稿が公開中（非表示・削除でない）のものの share UUID → post ID
     * （1 回の照会）。effort の F00 が投稿の非表示・削除状態を判定に含めるために読む（データ設計 §7.2）。
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> findLivePostIdsByShareUuids(Collection<UUID> shareUuids) {
        Map<UUID, Long> result = new HashMap<>();
        if (shareUuids == null || shareUuids.isEmpty()) {
            return result;
        }
        sources.findLiveByShareUuidIn(shareUuids).forEach(s -> result.put(s.getShareUuid(), s.getTimelinePostId()));
        return result;
    }

    /** share UUID の集合のうち、対応行がまだ失効していないもの（effort の保守が後処理の残りを判定する。1 回の照会）。 */
    @Transactional(readOnly = true)
    public Set<UUID> findUnrevokedShareUuids(Collection<UUID> shareUuids) {
        if (shareUuids == null || shareUuids.isEmpty()) {
            return Set.of();
        }
        return sources.findByShareUuidInAndIsRevokedFalse(shareUuids).stream()
                .map(TimelineEffortSourceEntity::getShareUuid)
                .collect(Collectors.toSet());
    }

    /** 投稿 ID 集合のうち努力由来のものの post ID → share UUID（1 回の照会）。 */
    @Transactional(readOnly = true)
    public Map<Long, UUID> findShareUuidsByPostIds(Collection<Long> postIds) {
        Map<Long, UUID> result = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        sources.findByTimelinePostIdIn(postIds).forEach(s -> result.put(s.getTimelinePostId(), s.getShareUuid()));
        return result;
    }
}
