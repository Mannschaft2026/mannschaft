package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 チーム側共有行のリポジトリ。
 *
 * <p>チーム側の経路キーは teamId。組織 ID では束ねない（2026-11-18 マスター裁定）。論理削除行（チーム削除後）は
 * {@code @SQLRestriction} で読まない。一覧・走査・F00 用の照会は {@link EffortTeamShareQueryRepository}。</p>
 */
public interface EffortTeamShareRepository extends JpaRepository<EffortTeamShareEntity, UUID> {

    /** (team, 原本) の共有行（予約 share UUID の再利用・状態確認用）。 */
    Optional<EffortTeamShareEntity> findByTeamIdAndSourceRecordId(Long teamId, UUID sourceRecordId);

    /** (team, 原本) の共有行を current read で行ロックする（upsert・取消の直列化）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM EffortTeamShareEntity s WHERE s.teamId = :teamId AND s.sourceRecordId = :recordId")
    Optional<EffortTeamShareEntity> findForUpdate(@Param("teamId") Long teamId, @Param("recordId") UUID sourceRecordId);

    /** チームの共有行を share UUID で取得する（別チームの UUID は空）。 */
    Optional<EffortTeamShareEntity> findByIdAndTeamId(UUID id, Long teamId);

    /** チームの共有行を share UUID で行ロックする（管理者非表示）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM EffortTeamShareEntity s WHERE s.id = :id AND s.teamId = :teamId")
    Optional<EffortTeamShareEntity> findByIdAndTeamIdForUpdate(@Param("id") UUID id, @Param("teamId") Long teamId);

    /** share UUID で行ロックする（通報による非表示。チームは対応表から得る）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM EffortTeamShareEntity s WHERE s.id = :id")
    Optional<EffortTeamShareEntity> findByIdForUpdate(@Param("id") UUID id);
}
