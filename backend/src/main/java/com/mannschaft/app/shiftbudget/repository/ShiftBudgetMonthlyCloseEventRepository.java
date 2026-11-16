package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * CMP-260909-2152 月次締めイベント (c) のリポジトリ（shiftbudget 自ドメイン）。
 */
public interface ShiftBudgetMonthlyCloseEventRepository extends JpaRepository<ShiftBudgetMonthlyCloseEventEntity, UUID> {

    Optional<ShiftBudgetMonthlyCloseEventEntity> findByAllocationIdAndTargetMonth(Long allocationId, String targetMonth);

    boolean existsByAllocationId(Long allocationId);

    boolean existsByAllocationIdAndTargetMonth(Long allocationId, String targetMonth);
}
