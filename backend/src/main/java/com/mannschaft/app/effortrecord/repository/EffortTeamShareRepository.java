package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * F06.4.1 チーム側共有行のリポジトリ（PR-3a 骨格。照会メソッドは PR-3b で足す）。
 *
 * <p>チーム側の経路キーは teamId。組織 ID では束ねない（2026-11-18 マスター裁定）。</p>
 */
public interface EffortTeamShareRepository extends JpaRepository<EffortTeamShareEntity, UUID> {
}
