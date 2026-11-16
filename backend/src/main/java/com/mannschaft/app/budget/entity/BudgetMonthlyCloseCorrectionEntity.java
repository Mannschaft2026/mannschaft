package com.mannschaft.app.budget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CMP-260909-2152 月次締め後の訂正履歴 (b)。追記専用（UPDATE / DELETE の経路を持たない）。
 *
 * <p>1行 = 1回の訂正・追加・取消。明細は line_kind で特定する。ORIGINAL は元の確定明細の消化行ID、
 * ADDED は締め後に追加した明細（ADD 行自身の ID）。before / after は明細1行ぶんの値で、
 * exact_delta は月合計（丸め前）の増減、posted_delta は月合計を丸めた値の増減（差額仕訳の金額）。</p>
 */
@Entity
@Table(name = "budget_monthly_close_corrections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BudgetMonthlyCloseCorrectionEntity extends UuidV7Entity {

    public static final String KIND_MODIFY = "MODIFY";
    public static final String KIND_CANCEL = "CANCEL";
    public static final String KIND_ADD = "ADD";
    public static final String KIND_REVERSAL = "REVERSAL";
    public static final String LINE_ORIGINAL = "ORIGINAL";
    public static final String LINE_ADDED = "ADDED";
    public static final String STATUS_ABSENT = "ABSENT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Column(name = "close_record_id", nullable = false)
    private UUID closeRecordId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "line_kind", nullable = false, length = 10)
    private String lineKind;

    @Column(name = "consumption_id")
    private Long consumptionId;

    @Column(name = "added_line_id")
    private UUID addedLineId;

    @Column(name = "reverses_correction_id")
    private UUID reversesCorrectionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "work_date")
    private LocalDate workDate;

    @Column(name = "slot_id")
    private Long slotId;

    @Column(name = "shift_id")
    private Long shiftId;

    @Column(name = "before_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal beforeHours;

    @Column(name = "before_hourly_rate", precision = 10, scale = 2)
    private BigDecimal beforeHourlyRate;

    @Column(name = "before_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal beforeAmount;

    @Column(name = "before_line_status", nullable = false, length = 10)
    private String beforeLineStatus;

    @Column(name = "after_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal afterHours;

    @Column(name = "after_hourly_rate", precision = 10, scale = 2)
    private BigDecimal afterHourlyRate;

    @Column(name = "after_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal afterAmount;

    @Column(name = "after_line_status", nullable = false, length = 10)
    private String afterLineStatus;

    @Column(name = "exact_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal exactDelta;

    @Column(name = "posted_delta", nullable = false, precision = 12, scale = 0)
    private BigDecimal postedDelta;

    @Column(name = "exact_month_total_after", nullable = false, precision = 14, scale = 2)
    private BigDecimal exactMonthTotalAfter;

    @Column(name = "content", length = 500)
    private String content;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(name = "budget_transaction_id")
    private Long budgetTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 訂正後の明細が集計に入る状態か。 */
    public boolean isAfterLive() {
        return STATUS_CONFIRMED.equals(afterLineStatus);
    }

    /** 訂正前の明細が集計に入る状態か。 */
    public boolean isBeforeLive() {
        return STATUS_CONFIRMED.equals(beforeLineStatus);
    }
}
