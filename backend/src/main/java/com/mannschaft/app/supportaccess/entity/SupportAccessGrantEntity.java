package com.mannschaft.app.supportaccess.entity;

import com.mannschaft.app.supportaccess.SupportAccessEndedReason;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * F10.9 サポート閲覧の依頼と本人許可（support_access_grants）。
 *
 * <p>状態遷移はドメインメソッドで<b>このインスタンスをその場で</b>書き換える（JPA の dirty checking 前提）。
 * open_flag は生成列なので insertable/updatable=false。Flyway は V274 で追加する。</p>
 */
@Entity
@Table(name = "support_access_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SupportAccessGrantEntity extends UuidV7Entity {

    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupportAccessStatus status;

    @Column(name = "request_reason", nullable = false, length = 1000)
    private String requestReason;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "request_expires_at", nullable = false)
    private Instant requestExpiresAt;

    /** 本人の許可/拒否の時刻。 */
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "deny_comment", length = 500)
    private String denyComment;

    /** 許可+24h（固定・延長不可）。 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ended_reason", length = 30)
    private SupportAccessEndedReason endedReason;

    @Column(name = "last_session_started_at")
    private Instant lastSessionStartedAt;

    /** 閲覧開始（POST sessions）の時刻。null なら未開始で、ヘッダーは 403。 */
    @Column(name = "active_session_started_at")
    private Instant activeSessionStartedAt;

    /** 閲覧終了の時刻。開始後に値が入ったらヘッダーは 403。 */
    @Column(name = "active_session_ended_at")
    private Instant activeSessionEndedAt;

    /** 未成年・後見下の本人で、保護者の承認も要るか。 */
    @Column(name = "guardian_approval_required", nullable = false)
    private boolean guardianApprovalRequired;

    @Column(name = "guardian_user_id")
    private Long guardianUserId;

    @Column(name = "guardian_decided_at")
    private Instant guardianDecidedAt;

    /** 生成列（PENDING/APPROVED のとき 1、それ以外 NULL）。一意制約で同じ組合せの同時1件を担保する。 */
    @Column(name = "open_flag", insertable = false, updatable = false,
            columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','APPROVED') THEN 1 ELSE NULL END) STORED")
    private Integer openFlag;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ─── 状態遷移（その場で書き換える） ─────────────────────────

    /** 依頼が開いている（PENDING/APPROVED）か。 */
    public boolean isOpen() {
        return status == SupportAccessStatus.PENDING || status == SupportAccessStatus.APPROVED;
    }

    /** 本人の許可を記録する。保護者の承認が要る grant で保護者が未承認なら PENDING のまま。 */
    public void recordTargetApproval(Instant now, long ttlHours) {
        this.decidedAt = now;
        promoteIfFullyApproved(now, ttlHours);
    }

    /** 保護者の許可を記録する。本人が未承認なら PENDING のまま。 */
    public void recordGuardianApproval(Instant now, long ttlHours) {
        this.guardianDecidedAt = now;
        promoteIfFullyApproved(now, ttlHours);
    }

    private void promoteIfFullyApproved(Instant now, long ttlHours) {
        boolean guardianOk = !guardianApprovalRequired || guardianDecidedAt != null;
        if (decidedAt != null && guardianOk) {
            this.status = SupportAccessStatus.APPROVED;
            // 期限は最後の許可時刻から固定（延長不可）
            this.expiresAt = now.plus(java.time.Duration.ofHours(ttlHours));
        }
    }

    /** 本人（asGuardian=false）または保護者（true）が許可済みか（二重許可の防止用）。 */
    public boolean hasApprovalBy(boolean asGuardian) {
        return asGuardian ? guardianDecidedAt != null : decidedAt != null;
    }

    public void deny(Instant now, String comment) {
        this.status = SupportAccessStatus.DENIED;
        this.decidedAt = now;
        this.denyComment = comment;
    }

    public void revoke(Instant now, SupportAccessEndedReason reason) {
        this.status = SupportAccessStatus.REVOKED;
        this.revokedAt = now;
        this.endedReason = reason;
    }

    public void cancel(Instant now) {
        this.status = SupportAccessStatus.CANCELLED;
        this.revokedAt = now;
        this.endedReason = SupportAccessEndedReason.OPERATOR_CANCELLED;
    }

    public void expire() {
        this.status = SupportAccessStatus.EXPIRED;
        this.endedReason = SupportAccessEndedReason.EXPIRED;
    }

    public void startSession(Instant now) {
        this.activeSessionStartedAt = now;
        this.activeSessionEndedAt = null;
        this.lastSessionStartedAt = now;
    }

    public void endSession(Instant now) {
        this.activeSessionEndedAt = now;
    }

    /**
     * 閲覧開始済みで、まだ終了していないか。
     *
     * <p>{@link #startSession} が終了時刻を null に戻し、{@link #endSession} が終了時刻を入れるため、
     * 「開始あり・終了なし」だけで判定する（開始と同じ時刻で終了した場合も終了済みとして扱う。時刻の前後比較に頼らない）。</p>
     */
    public boolean isSessionActive() {
        return activeSessionStartedAt != null && activeSessionEndedAt == null;
    }
}
