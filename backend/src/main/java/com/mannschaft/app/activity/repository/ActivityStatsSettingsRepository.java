package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityStatsSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 活動統計のスコープ別設定リポジトリ（F06.4 §15.3）。
 */
public interface ActivityStatsSettingsRepository extends JpaRepository<ActivityStatsSettingsEntity, UUID> {

    Optional<ActivityStatsSettingsEntity> findByScopeTypeAndScopeId(ActivityScopeType scopeType, Long scopeId);

    /** 退会者の物理削除時に最終更新者を NULL 化する（集合演算 1 本）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ActivityStatsSettingsEntity s SET s.updatedBy = NULL WHERE s.updatedBy = :userId")
    int clearUpdatedBy(@Param("userId") Long userId);
}
