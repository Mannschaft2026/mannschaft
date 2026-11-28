package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelineEffortSourceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F06.4.1 努力由来投稿の対応表リポジトリ（share UUID ↔ timeline_posts.id）。 */
public interface TimelineEffortSourceRepository extends JpaRepository<TimelineEffortSourceEntity, UUID> {

    /** share UUID の対応行を current read で行ロックする（upsert・revoke の直列化）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TimelineEffortSourceEntity s WHERE s.shareUuid = :shareUuid")
    Optional<TimelineEffortSourceEntity> findByShareUuidForUpdate(@Param("shareUuid") UUID shareUuid);

    Optional<TimelineEffortSourceEntity> findByTimelinePostId(Long timelinePostId);

    /** 投稿 ID 集合のうち努力由来のものの対応行（読み取り経路のガードで 1 回だけ引く）。 */
    List<TimelineEffortSourceEntity> findByTimelinePostIdIn(Collection<Long> timelinePostIds);

    /** share UUID の集合のうち、未失効で対応投稿が公開中（非表示・削除でない）の対応行（1 回の照会）。 */
    @Query("SELECT s FROM TimelineEffortSourceEntity s WHERE s.shareUuid IN :shareUuids AND s.isRevoked = false "
            + "AND EXISTS (SELECT p.id FROM TimelinePostEntity p WHERE p.id = s.timelinePostId "
            + "AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED AND p.deletedAt IS NULL)")
    List<TimelineEffortSourceEntity> findLiveByShareUuidIn(@Param("shareUuids") Collection<UUID> shareUuids);

    /** share UUID の集合のうち、未失効の対応行（保守の後処理判定。1 回の照会）。 */
    List<TimelineEffortSourceEntity> findByShareUuidInAndIsRevokedFalse(Collection<UUID> shareUuids);
}
