package com.mannschaft.app.supportaccess.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 遷移・拒否の監査の outbox（support_access_audit_outbox）。
 *
 * <p>遷移（依頼・許可・取消…）と<b>同じトランザクション</b>で1行書く。コミット後に
 * {@code SupportAccessAuditRecorder} が audit_logs へ反映し {@link #markPublished} する。反映に失敗した行は
 * {@code publishedAt} が null のまま残り、再試行バッチが拾う。遷移が確定したのに監査が無い、という状態を作らない。</p>
 *
 * <p>反映は「audit_logs への書込」と「本行の確定」が別トランザクション（auth と supportaccess を1つの TX に
 * まとめない: D-3T）のため、少なくとも1回（at-least-once）。重複した場合は audit_logs の metadata の
 * {@code auditOutboxId} で同一の事実と分かる。</p>
 */
@Entity
@Table(name = "support_access_audit_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SupportAccessAuditOutboxEntity extends UuidV7Entity {

    /** 反映失敗時の last_error の最大長。 */
    public static final int MAX_ERROR_LENGTH = 500;

    @Column(name = "grant_id", columnDefinition = "BINARY(16)")
    private UUID grantId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "operator_user_id")
    private Long operatorUserId;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 反映を担当する（リース）。リース中は他の反映役（即時・バッチ）が同じ行を取らない。 */
    public void lease(Instant leaseUntil) {
        this.attemptCount = attemptCount + 1;
        this.nextAttemptAt = leaseUntil;
    }

    public void markPublished(Instant now) {
        this.publishedAt = now;
        this.lastError = null;
    }

    /** 反映に失敗した。retryAt 以降に再試行バッチが拾う。 */
    public void markFailed(String error, Instant retryAt) {
        String summary = error == null ? "unknown" : error;
        this.lastError = summary.length() > MAX_ERROR_LENGTH ? summary.substring(0, MAX_ERROR_LENGTH) : summary;
        this.nextAttemptAt = retryAt;
    }
}
