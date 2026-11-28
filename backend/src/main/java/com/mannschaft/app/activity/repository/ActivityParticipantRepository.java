package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 活動参加者リポジトリ。
 */
public interface ActivityParticipantRepository extends JpaRepository<ActivityParticipantEntity, Long> {

    List<ActivityParticipantEntity> findByActivityResultIdOrderByCreatedAtAsc(Long activityResultId);

    Optional<ActivityParticipantEntity> findByActivityResultIdAndUserId(Long activityResultId, Long userId);

    void deleteByActivityResultIdAndUserIdIn(Long activityResultId, List<Long> userIds);


    long countByActivityResultId(Long activityResultId);

    long countByUserId(Long userId);

    // ───────── 活動統計（F06.4 §15.2・CMP-261124-0159）。対象記録の述語は ActivityResultRepository の統計と同じ ─────────

    /** (3) 延べ参加数と実人数。行: [participations, uniqueParticipants]。 */
    @Query("""
            SELECT COUNT(p.id), COUNT(DISTINCT p.userId) FROM ActivityParticipantEntity p
            WHERE p.activityResultId IN (SELECT r.id FROM ActivityResultEntity r WHERE r.scopeType = :scopeType AND r.scopeId = :scopeId
            AND r.status = com.mannschaft.app.activity.ActivityStatus.PUBLISHED
            AND r.activityDate BETWEEN :dateFrom AND :dateTo
            AND (:templateId IS NULL OR r.templateId = :templateId))
            """)
    List<Object[]> countStatsParticipants(@Param("scopeType") ActivityScopeType scopeType, @Param("scopeId") Long scopeId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo, @Param("templateId") Long templateId);

    /** (4) 上位参加者（参加回数の降順・同数は userId 昇順）。行: [userId, participationCount]。件数は Pageable で絞る。 */
    @Query("""
            SELECT p.userId, COUNT(p.id) FROM ActivityParticipantEntity p
            WHERE p.userId IS NOT NULL
            AND p.activityResultId IN (SELECT r.id FROM ActivityResultEntity r WHERE r.scopeType = :scopeType AND r.scopeId = :scopeId
            AND r.status = com.mannschaft.app.activity.ActivityStatus.PUBLISHED
            AND r.activityDate BETWEEN :dateFrom AND :dateTo
            AND (:templateId IS NULL OR r.templateId = :templateId))
            GROUP BY p.userId
            ORDER BY COUNT(p.id) DESC, p.userId ASC
            """)
    List<Object[]> findStatsTopParticipants(@Param("scopeType") ActivityScopeType scopeType, @Param("scopeId") Long scopeId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo, @Param("templateId") Long templateId,
            org.springframework.data.domain.Pageable pageable);
}
