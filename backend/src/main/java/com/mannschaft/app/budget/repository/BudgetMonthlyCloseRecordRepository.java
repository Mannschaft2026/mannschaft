package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetMonthlyCloseRecordEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CMP-260909-2152 月次締め記録 (a) のリポジトリ。
 */
public interface BudgetMonthlyCloseRecordRepository extends JpaRepository<BudgetMonthlyCloseRecordEntity, UUID> {

    Optional<BudgetMonthlyCloseRecordEntity> findByOriginalTransactionId(Long originalTransactionId);

    /** 訂正 TX が締め記録を直列化するための行ロック付き取得。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BudgetMonthlyCloseRecordEntity r WHERE r.originalTransactionId = :transactionId")
    Optional<BudgetMonthlyCloseRecordEntity> findByOriginalTransactionIdForUpdate(
            @Param("transactionId") Long transactionId);

    List<BudgetMonthlyCloseRecordEntity> findByAllocationId(Long allocationId);

    /**
     * 割当ごとの訂正差額合計（訂正後の月合計 − 締めた時点の月合計）。割当数に依らず1クエリ。
     * 戻り値は [allocationId, 差額合計] の組。
     */
    @Query("SELECT r.allocationId, SUM(r.postedTotal - r.originalAmount) FROM BudgetMonthlyCloseRecordEntity r "
            + "WHERE r.allocationId IN :allocationIds GROUP BY r.allocationId")
    List<Object[]> sumCorrectionDeltaByAllocationIds(@Param("allocationIds") Collection<Long> allocationIds);
}
