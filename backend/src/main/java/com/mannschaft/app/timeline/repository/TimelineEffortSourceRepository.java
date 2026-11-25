package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelineEffortSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** F06.4.1 努力由来投稿の対応表リポジトリ（PR-3a 骨格。照会・CAS 更新は PR-3b で足す）。 */
public interface TimelineEffortSourceRepository extends JpaRepository<TimelineEffortSourceEntity, UUID> {
}
