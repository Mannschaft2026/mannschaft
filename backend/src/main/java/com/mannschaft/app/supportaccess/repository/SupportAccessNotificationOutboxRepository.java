package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.entity.SupportAccessNotificationOutboxEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F10.9 support_access_notification_outbox のリポジトリ。
 *
 * <p>配送役の取得は {@code FOR UPDATE SKIP LOCKED}（{@code jakarta.persistence.lock.timeout = -2}。Hibernate の
 * MySQL 方言が解釈する値）で、即時配送と再試行バッチが同じ行を待ち合わずに分け合う。</p>
 */
public interface SupportAccessNotificationOutboxRepository
        extends JpaRepository<SupportAccessNotificationOutboxEntity, UUID> {

    /** 未配送でリースの切れた行（再試行バッチ用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from SupportAccessNotificationOutboxEntity o "
            + "where o.publishedAt is null and o.nextAttemptAt <= :now order by o.id")
    List<SupportAccessNotificationOutboxEntity> findDueForUpdate(@Param("now") Instant now, Pageable pageable);

    /** 指定 grant の未配送でリースの切れた行（遷移直後の即時配送用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from SupportAccessNotificationOutboxEntity o "
            + "where o.grantId = :grantId and o.publishedAt is null and o.nextAttemptAt <= :now order by o.id")
    List<SupportAccessNotificationOutboxEntity> findDueByGrantIdForUpdate(@Param("grantId") UUID grantId,
                                                                          @Param("now") Instant now);

    /** 冪等キーの行が既にあるか（同じ遷移の二重書込を防ぐ）。 */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /** 未配送の件数（監視・テスト用）。 */
    long countByPublishedAtIsNull();

    /** 退会30日後の強消去: 本人宛・本人に関わる行を物理削除する（本文は配送時に組むため、行に本文は無い）。 */
    @Modifying
    @Query("delete from SupportAccessNotificationOutboxEntity o "
            + "where o.recipientUserId = :userId or o.targetUserId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** 配送済みで保存期間を過ぎた行の物理削除。 */
    @Modifying
    @Query("delete from SupportAccessNotificationOutboxEntity o where o.publishedAt is not null and o.publishedAt < :threshold")
    int deletePublishedBefore(@Param("threshold") Instant threshold);
}
