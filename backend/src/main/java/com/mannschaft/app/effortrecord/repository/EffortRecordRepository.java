package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 個人原本リポジトリ。
 *
 * <p>private の owner スコープ Repository は ownerUserId を必須条件とする（organization_id では束ねない）。
 * 通常の取得は {@code @SQLRestriction}（削除済み・purged 除外）が効く。共有の後処理（coordinator）は原本 ID だけで
 * 行ロックを取る（{@link #findByIdForUpdate}。owner の認可は共有要求の受付時に済んでいる）。</p>
 */
public interface EffortRecordRepository extends JpaRepository<EffortRecordEntity, UUID> {

    /** 本人の原本を取得する（他人・不在・削除済み・purged は空）。 */
    Optional<EffortRecordEntity> findByIdAndOwnerUserId(UUID id, Long ownerUserId);

    /** 本人の原本を current read で行ロックする（共有の要求・取消・retry の直列化）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EffortRecordEntity r WHERE r.id = :id AND r.ownerUserId = :owner")
    Optional<EffortRecordEntity> findOwnedForUpdate(@Param("id") UUID id, @Param("owner") Long ownerUserId);

    /** 共有の後処理（ack・失敗記録・MODERATED 反映）用に原本を行ロックする。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EffortRecordEntity r WHERE r.id = :id")
    Optional<EffortRecordEntity> findByIdForUpdate(@Param("id") UUID id);
}
