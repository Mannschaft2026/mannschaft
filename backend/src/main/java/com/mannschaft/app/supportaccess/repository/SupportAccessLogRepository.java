package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** F10.9 support_access_logs のリポジトリ。 */
public interface SupportAccessLogRepository extends JpaRepository<SupportAccessLogEntity, UUID> {

    List<SupportAccessLogEntity> findByTargetUserIdOrderByOccurredAtDesc(Long targetUserId);

    List<SupportAccessLogEntity> findByGrantIdOrderByOccurredAtAsc(UUID grantId);

    long deleteByTargetUserId(Long targetUserId);

    /** 保存期間（1年）を過ぎた閲覧記録の物理削除。 */
    long deleteByOccurredAtBefore(LocalDateTime threshold);
}
