package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.entity.SupportAccessAuditOutboxEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** F10.9 support_access_audit_outbox のリポジトリ。 */
public interface SupportAccessAuditOutboxRepository extends JpaRepository<SupportAccessAuditOutboxEntity, UUID> {

    /** 未反映でリースの切れた行（再試行バッチ・即時反映の取得用）。行ロックを取り、id 順で取る。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SupportAccessAuditOutboxEntity o "
            + "where o.publishedAt is null and o.nextAttemptAt <= :now order by o.id")
    List<SupportAccessAuditOutboxEntity> findDueForUpdate(@Param("now") Instant now, Pageable pageable);

    /** 指定 grant の未反映でリースの切れた行（遷移直後の即時反映用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SupportAccessAuditOutboxEntity o "
            + "where o.grantId = :grantId and o.publishedAt is null and o.nextAttemptAt <= :now order by o.id")
    List<SupportAccessAuditOutboxEntity> findDueByGrantIdForUpdate(@Param("grantId") UUID grantId,
                                                                   @Param("now") Instant now);

    /** 未反映の件数（監視・テスト用）。 */
    long countByPublishedAtIsNull();

    /**
     * 退会30日後の強消去: 本人宛の監査 outbox を物理削除する（反映済みの監査は audit_logs 側の匿名化方針に従う）。
     * 運営アカウントの削除では消さない（grants・logs と同じく ID 参照のまま残す）。
     */
    @Modifying
    @Query("delete from SupportAccessAuditOutboxEntity o where o.targetUserId = :targetUserId")
    int deleteByTargetUserId(@Param("targetUserId") Long targetUserId);

    /** 反映済みで保存期間を過ぎた行の物理削除。 */
    @Modifying
    @Query("delete from SupportAccessAuditOutboxEntity o where o.publishedAt is not null and o.publishedAt < :threshold")
    int deletePublishedBefore(@Param("threshold") Instant threshold);
}
