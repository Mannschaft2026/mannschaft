package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetMonthlyCloseCorrectionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CMP-260909-2152 月次締め後の訂正履歴 (b) のリポジトリ。書込みは INSERT のみ（UPDATE / DELETE を呼ばない）。
 */
public interface BudgetMonthlyCloseCorrectionRepository
        extends JpaRepository<BudgetMonthlyCloseCorrectionEntity, UUID> {

    Optional<BudgetMonthlyCloseCorrectionEntity> findByCommandId(UUID commandId);

    Optional<BudgetMonthlyCloseCorrectionEntity> findByIdAndCloseRecordId(UUID id, UUID closeRecordId);

    Optional<BudgetMonthlyCloseCorrectionEntity> findFirstByCloseRecordIdAndConsumptionIdOrderByRevisionDesc(
            UUID closeRecordId, Long consumptionId);

    Optional<BudgetMonthlyCloseCorrectionEntity> findFirstByCloseRecordIdAndAddedLineIdOrderByRevisionDesc(
            UUID closeRecordId, UUID addedLineId);

    /** 履歴の1ページ（revision 昇順）。 */
    Page<BudgetMonthlyCloseCorrectionEntity> findByCloseRecordIdOrderByRevisionAsc(UUID closeRecordId, Pageable pageable);

    /** 同じ勤務枠・同じ人で追加した明細の ADD 行（復活・追加時の重複検査用）。 */
    List<BudgetMonthlyCloseCorrectionEntity> findByCloseRecordIdAndKindAndSlotIdAndUserId(
            UUID closeRecordId, String kind, Long slotId, Long userId);

    /**
     * 各明細の最新の訂正行だけを返す（元の確定明細は消化行ID、追加明細は追加明細ID が同じ行を1明細とみなす）。
     * 訂正が何件あっても明細数にしか依存しない。
     */
    @Query("SELECT c FROM BudgetMonthlyCloseCorrectionEntity c WHERE c.closeRecordId IN :recordIds "
            + "AND c.revision = (SELECT MAX(c2.revision) FROM BudgetMonthlyCloseCorrectionEntity c2 "
            + "WHERE c2.closeRecordId = c.closeRecordId "
            + "AND ((c.consumptionId IS NOT NULL AND c2.consumptionId = c.consumptionId) "
            + "OR (c.addedLineId IS NOT NULL AND c2.addedLineId = c.addedLineId)))")
    List<BudgetMonthlyCloseCorrectionEntity> findLatestPerLine(@Param("recordIds") Collection<UUID> recordIds);
}
