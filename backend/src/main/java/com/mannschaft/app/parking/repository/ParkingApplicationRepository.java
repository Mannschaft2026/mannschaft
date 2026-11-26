package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.ParkingApplicationStatus;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 区画申請リポジトリ。
 */
public interface ParkingApplicationRepository extends JpaRepository<ParkingApplicationEntity, Long>,
        JpaSpecificationExecutor<ParkingApplicationEntity> {

    Page<ParkingApplicationEntity> findBySpaceIdIn(List<Long> spaceIds, Pageable pageable);

    Page<ParkingApplicationEntity> findBySpaceIdInAndStatus(List<Long> spaceIds, ParkingApplicationStatus status, Pageable pageable);

    /** 本人分のみの一覧（MANAGE_PARKING を持たない閲覧者向け。CMP-260930-0233）。 */
    Page<ParkingApplicationEntity> findBySpaceIdInAndUserId(List<Long> spaceIds, Long userId, Pageable pageable);

    Page<ParkingApplicationEntity> findBySpaceIdInAndUserIdAndStatus(
            List<Long> spaceIds, Long userId, ParkingApplicationStatus status, Pageable pageable);

    Optional<ParkingApplicationEntity> findBySpaceIdAndUserIdAndStatusIn(Long spaceId, Long userId, List<ParkingApplicationStatus> statuses);

    List<ParkingApplicationEntity> findBySpaceIdAndStatus(Long spaceId, ParkingApplicationStatus status);

    long countBySpaceIdInAndStatus(List<Long> spaceIds, ParkingApplicationStatus status);
}
