package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 個人原本リポジトリ。
 *
 * <p>private の owner スコープ Repository は ownerUserId を必須条件とする（organization_id では束ねない）。
 * 通常の取得は {@code @SQLRestriction}（削除済み・purged 除外）が効く。</p>
 */
public interface EffortRecordRepository extends JpaRepository<EffortRecordEntity, UUID> {

    /** 本人の原本を取得する（他人・不在・削除済み・purged は空）。 */
    Optional<EffortRecordEntity> findByIdAndOwnerUserId(UUID id, Long ownerUserId);
}
