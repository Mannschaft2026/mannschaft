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

import java.time.LocalDateTime;

/**
 * F10.9 サポート閲覧の依頼と本人許可（support_access_grants）。試練スタブ: 列だけを持つ。
 *
 * <p>状態遷移は実装役がドメインメソッドとして加え、<b>このインスタンスをその場で</b>書き換える
 * （JPA の dirty checking 前提。試練はその場の状態を検査する）。open_flag は生成列なので
 * insertable/updatable=false。Flyway は実装役が V269 で追加する。</p>
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
    private LocalDateTime requestedAt;

    @Column(name = "request_expires_at", nullable = false)
    private LocalDateTime requestExpiresAt;

    /** 本人の許可/拒否の時刻。 */
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "deny_comment", length = 500)
    private String denyComment;

    /** 許可+24h（固定・延長不可）。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ended_reason", length = 30)
    private SupportAccessEndedReason endedReason;

    @Column(name = "last_session_started_at")
    private LocalDateTime lastSessionStartedAt;

    /** 閲覧開始（POST sessions）の時刻。null なら未開始で、ヘッダーは 403。 */
    @Column(name = "active_session_started_at")
    private LocalDateTime activeSessionStartedAt;

    /** 閲覧終了の時刻。開始後に値が入ったらヘッダーは 403。 */
    @Column(name = "active_session_ended_at")
    private LocalDateTime activeSessionEndedAt;

    /** 未成年・後見下の本人で、保護者の承認も要るか。 */
    @Column(name = "guardian_approval_required", nullable = false)
    private boolean guardianApprovalRequired;

    @Column(name = "guardian_user_id")
    private Long guardianUserId;

    @Column(name = "guardian_decided_at")
    private LocalDateTime guardianDecidedAt;

    /** 生成列（PENDING/APPROVED のとき 1、それ以外 NULL）。一意制約で同じ組合せの同時1件を担保する。 */
    @Column(name = "open_flag", insertable = false, updatable = false)
    private Integer openFlag;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
