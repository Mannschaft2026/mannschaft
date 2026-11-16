package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * CMP-260909-2152 月次締めイベント (c)。締め TX の中で shiftbudget が自ドメインの表として INSERT する。
 *
 * <p>締め操作者の正本（手動=MANUAL+利用者ID、cron=BATCH、本表導入前の締め=UNKNOWN）。
 * 割当作成者・仕訳記録者を操作者として扱わない。締め済みの月は、再公開で消化を作らない判定と、
 * 割当の削除拒否（HAS_CLOSED_MONTHS）にも使う。</p>
 */
@Entity
@Table(name = "shift_budget_monthly_close_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftBudgetMonthlyCloseEventEntity extends UuidV7Entity {

    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_BATCH = "BATCH";
    public static final String KIND_UNKNOWN = "UNKNOWN";

    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    /** 締め対象月 yyyy-MM。 */
    @Column(name = "target_month", nullable = false, length = 7)
    private String targetMonth;

    @Column(name = "actor_kind", nullable = false, length = 10)
    private String actorKind;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
