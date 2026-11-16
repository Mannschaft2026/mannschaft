package com.mannschaft.app.budget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CMP-260909-2152 月次締め記録 (a)。割当×月の「締めた事実」と、現在の訂正後合計を持つ。
 *
 * <p>最初の訂正を受け付けた時点で、同じ TX の中で作成する（原仕訳の行ロック下）。
 * 締め操作者の正本は shiftbudget の締めイベントで、ここへは値で写す。
 * 原仕訳・元の消化行は書き換えず、訂正は {@link BudgetMonthlyCloseCorrectionEntity} へ追記する。</p>
 */
@Entity
@Table(name = "budget_monthly_close_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BudgetMonthlyCloseRecordEntity extends UuidV7Entity {

    @Column(name = "original_transaction_id", nullable = false)
    private Long originalTransactionId;

    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    /** 締め対象月 yyyy-MM。 */
    @Column(name = "target_month", nullable = false, length = 7)
    private String targetMonth;

    @Column(name = "original_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal originalAmount;

    @Column(name = "original_line_count", nullable = false)
    private int originalLineCount;

    @Column(name = "original_exact_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal originalExactTotal;

    @Column(name = "close_actor_kind", nullable = false, length = 10)
    private String closeActorKind;

    @Column(name = "close_actor_user_id")
    private Long closeActorUserId;

    @Column(name = "original_recorded_by", nullable = false)
    private Long originalRecordedBy;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "posted_total", nullable = false, precision = 12, scale = 0)
    private BigDecimal postedTotal;

    @Column(name = "exact_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal exactTotal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 訂正を1件追記した結果を反映する。 */
    public void applyCorrection(long newRevision, BigDecimal newExactTotal, BigDecimal newPostedTotal) {
        this.revision = newRevision;
        this.exactTotal = newExactTotal;
        this.postedTotal = newPostedTotal;
    }
}
