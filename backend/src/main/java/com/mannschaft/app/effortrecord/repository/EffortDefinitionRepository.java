package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F06.4.1 定義リポジトリ。PRESET は全員共通の参照データ。 */
public interface EffortDefinitionRepository extends JpaRepository<EffortDefinitionEntity, UUID> {

    Optional<EffortDefinitionEntity> findBySourceTypeAndSourceKey(EffortSourceType sourceType, String sourceKey);

    /** 標準プリセット（STUDY_V1 / EXERCISE_V1 / WEIGHT_V1）を source_key 昇順で取得する。 */
    List<EffortDefinitionEntity> findBySourceTypeOrderBySourceKeyAsc(EffortSourceType sourceType);
}
