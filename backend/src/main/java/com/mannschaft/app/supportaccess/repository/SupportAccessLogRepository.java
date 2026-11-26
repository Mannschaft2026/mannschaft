package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F10.9 support_access_logs のリポジトリ。 */
public interface SupportAccessLogRepository extends JpaRepository<SupportAccessLogEntity, UUID> {

    List<SupportAccessLogEntity> findByTargetUserIdOrderByOccurredAtDesc(Long targetUserId);

    /** 本人向けの閲覧記録（新しい順・カーソルは occurredAt）。 */
    List<SupportAccessLogEntity> findByTargetUserIdAndOccurredAtBeforeOrderByOccurredAtDesc(
            Long targetUserId, LocalDateTime before, Pageable pageable);

    /** 本人向けの閲覧記録（grant を絞る）。 */
    List<SupportAccessLogEntity> findByTargetUserIdAndGrantIdAndOccurredAtBeforeOrderByOccurredAtDesc(
            Long targetUserId, UUID grantId, LocalDateTime before, Pageable pageable);

    List<SupportAccessLogEntity> findByGrantIdOrderByOccurredAtAsc(UUID grantId);

    /** 運営側の詳細（新しい順・カーソルは occurredAt）。 */
    List<SupportAccessLogEntity> findByGrantIdAndOccurredAtBeforeOrderByOccurredAtDesc(
            UUID grantId, LocalDateTime before, Pageable pageable);

    /** 退会30日後の強消去: 本人の閲覧記録を物理削除する。 */
    @Modifying
    @Query("delete from SupportAccessLogEntity l where l.targetUserId = :targetUserId")
    int deleteByTargetUserId(@Param("targetUserId") Long targetUserId);

    /** 保存期間（1年）を過ぎた閲覧記録の物理削除。 */
    @Modifying
    @Query("delete from SupportAccessLogEntity l where l.occurredAt < :threshold")
    int deleteByOccurredAtBefore(@Param("threshold") LocalDateTime threshold);

    /** 拒否の記録用: grant が実在するなら本人（閲覧された側）の userId を返す。 */
    @Query("select g.targetUserId from SupportAccessGrantEntity g where g.id = :grantId")
    Optional<Long> findTargetUserIdByGrantId(@Param("grantId") UUID grantId);
}
