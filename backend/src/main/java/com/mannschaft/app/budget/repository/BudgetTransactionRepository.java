package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * 予算取引リポジトリ。
 */
public interface BudgetTransactionRepository extends JpaRepository<BudgetTransactionEntity, Long> {

    /**
     * 年度IDで取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearId(Long fiscalYearId);

    /**
     * 年度IDで取引をページネーション検索する。
     */
    Page<BudgetTransactionEntity> findByFiscalYearId(Long fiscalYearId, Pageable pageable);

    /**
     * 費目IDで取引を検索する。
     */
    List<BudgetTransactionEntity> findByCategoryId(Long categoryId);

    /**
     * スコープで取引を検索する。
     */
    List<BudgetTransactionEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * 取引日の範囲で取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearIdAndTransactionDateBetween(Long fiscalYearId, LocalDate start, LocalDate end);

    /**
     * 承認ステータスで取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearIdAndApprovalStatus(Long fiscalYearId, BudgetApprovalStatus approvalStatus);

    /**
     * ソースで取引を検索する。
     */
    List<BudgetTransactionEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * 記録者で取引を検索する。
     */
    List<BudgetTransactionEntity> findByRecordedBy(Long recordedBy);

    /**
     * 年度IDで取引数をカウントする。
     */
    long countByFiscalYearId(Long fiscalYearId);

    /** 年度削除の監査履歴保護用。SQLRestrictionを迂回し論理削除済み取引も数える。 */
    @Query(value = "SELECT COUNT(*) FROM budget_transactions WHERE fiscal_year_id = :fiscalYearId", nativeQuery = true)
    long countAllHistoryByFiscalYearId(@Param("fiscalYearId") Long fiscalYearId);

    /**
     * 年度IDと費目IDで承認済み取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearIdAndCategoryIdAndApprovalStatus(Long fiscalYearId, Long categoryId, BudgetApprovalStatus approvalStatus);

    /**
     * 同一 (source_type, source_id, transaction_date) の取引が存在するかを判定する。
     *
     * <p>F08.7 Phase 9-δ 月次締めの冪等性保証用:
     * {@code source_type='SHIFT_BUDGET_MONTHLY'} かつ {@code source_id=allocation_id} かつ
     * {@code transaction_date=lastDayOfMonth} のレコード既存をチェックし、
     * 二重仕訳を防ぐ。設計書 §4.6 / §6.1 #11 参照。</p>
     */
    boolean existsBySourceTypeAndSourceIdAndTransactionDate(
            String sourceType, Long sourceId, LocalDate transactionDate);

    /**
     * CMP-260909-2152: 月次仕訳を行ロックなしで探す（生存のみ。V209 の論理削除は SQLRestriction で除外）。
     * 年度行を先にロックするため、年度IDを知る目的で使う。
     */
    List<BudgetTransactionEntity> findBySourceTypeAndSourceIdAndTransactionDate(
            String sourceType, Long sourceId, LocalDate transactionDate);

    /** CMP-260909-2152: 月次仕訳を {@code FOR UPDATE} で読む。訂正 TX は年度行のロックの後にここへ来る。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM BudgetTransactionEntity t WHERE t.sourceType = :sourceType AND t.sourceId = :sourceId "
            + "AND t.transactionDate = :transactionDate ORDER BY t.id")
    List<BudgetTransactionEntity> findBySourceForUpdate(@Param("sourceType") String sourceType,
                                                        @Param("sourceId") Long sourceId,
                                                        @Param("transactionDate") LocalDate transactionDate);
}
