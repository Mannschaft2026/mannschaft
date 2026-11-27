package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F10.9 support_access_grants のリポジトリ。 */
public interface SupportAccessGrantRepository extends JpaRepository<SupportAccessGrantEntity, UUID> {

    /** 同じ運営者×本人の開いている（PENDING/APPROVED）grant。 */
    Optional<SupportAccessGrantEntity> findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(
            Long operatorUserId, Long targetUserId, Collection<SupportAccessStatus> statuses);

    List<SupportAccessGrantEntity> findByTargetUserIdAndStatusIn(Long targetUserId, Collection<SupportAccessStatus> statuses);

    List<SupportAccessGrantEntity> findByStatusAndRequestExpiresAtBefore(SupportAccessStatus status, Instant threshold);

    List<SupportAccessGrantEntity> findByStatusAndExpiresAtBefore(SupportAccessStatus status, Instant threshold);

    /** 運営者ごとの1日あたり依頼数（011 の判定）。 */
    long countByOperatorUserIdAndRequestedAtAfter(Long operatorUserId, Instant since);

    /** 運営者の自分の依頼（新しい順）。カーソルは requestedAt（これより前）。 */
    List<SupportAccessGrantEntity> findByOperatorUserIdAndRequestedAtBeforeOrderByRequestedAtDesc(
            Long operatorUserId, Instant before, Pageable pageable);

    /** 本人宛・保護者宛の依頼（新しい順）。 */
    @Query("select g from SupportAccessGrantEntity g "
            + "where (g.targetUserId = :userId or (g.guardianApprovalRequired = true and g.guardianUserId = :userId)) "
            + "and g.requestedAt < :before order by g.requestedAt desc")
    List<SupportAccessGrantEntity> findVisibleToUser(@Param("userId") Long userId,
                                                     @Param("before") Instant before, Pageable pageable);

    /** 退会30日後の強消去: 本人宛の許可を物理削除する。 */
    @Modifying
    @Query("delete from SupportAccessGrantEntity g where g.targetUserId = :targetUserId")
    int deleteByTargetUserId(@Param("targetUserId") Long targetUserId);

    /** 保存期間（終了から3年）を過ぎた終了済み grant の物理削除。 */
    @Modifying
    @Query("delete from SupportAccessGrantEntity g where g.status in :statuses and g.updatedAt < :threshold")
    int deleteByStatusInAndUpdatedAtBefore(@Param("statuses") Collection<SupportAccessStatus> statuses,
                                           @Param("threshold") Instant threshold);
}
