package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F10.9 support_access_grants のリポジトリ。派生クエリのみ（起動を壊さないため @Query は実装役が加える）。 */
public interface SupportAccessGrantRepository extends JpaRepository<SupportAccessGrantEntity, UUID> {

    /** 同じ運営者×本人の開いている（PENDING/APPROVED）grant。 */
    Optional<SupportAccessGrantEntity> findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(
            Long operatorUserId, Long targetUserId, Collection<SupportAccessStatus> statuses);

    List<SupportAccessGrantEntity> findByTargetUserIdAndStatusIn(Long targetUserId, Collection<SupportAccessStatus> statuses);

    List<SupportAccessGrantEntity> findByStatusAndRequestExpiresAtBefore(SupportAccessStatus status, LocalDateTime threshold);

    List<SupportAccessGrantEntity> findByStatusAndExpiresAtBefore(SupportAccessStatus status, LocalDateTime threshold);

    /** 運営者ごとの1日あたり依頼数（011 の判定）。 */
    long countByOperatorUserIdAndRequestedAtAfter(Long operatorUserId, LocalDateTime since);

    long deleteByTargetUserId(Long targetUserId);

    /** 保存期間（終了から3年）を過ぎた終了済み grant の物理削除。 */
    long deleteByStatusInAndUpdatedAtBefore(Collection<SupportAccessStatus> statuses, LocalDateTime threshold);
}
