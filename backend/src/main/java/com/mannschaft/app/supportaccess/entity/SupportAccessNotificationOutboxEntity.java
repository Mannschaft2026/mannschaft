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
 * F10.9 アプリ内通知の outbox（support_access_notification_outbox）。
 *
 * <p>遷移（依頼・閲覧開始・許可・拒否・取消）と<b>同じトランザクション</b>で宛先ごとに1行書く（第2回検分対応）。
 * 以前は AFTER_COMMIT の {@code @Async} リスナーが後から通知を作っていたため、再起動・プールの拒否・例外で
 * 閲覧開始の通知が消えた。コミット後に {@code SupportAccessNotificationListener} が配送して {@link #markPublished}
 * し、失敗・未配送の行は {@code publishedAt} が null のまま残って再試行バッチが拾う。</p>
 *
 * <p>冪等キーは「種別:grantId:宛先（閲覧開始は開始時刻のエポックミリ秒も）」で決まる値で、UNIQUE にしてある。
 * 配送は「通知の作成（notification ドメイン）」と「本行の確定」が別トランザクションのため少なくとも1回
 * （at-least-once）。メールは同じ遷移の TX でメール outbox に直接 enqueue する（本表には載せない）。</p>
 */
@Entity
@Table(name = "support_access_notification_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SupportAccessNotificationOutboxEntity extends UuidV7Entity {

    /** 配送失敗時の last_error の最大長。 */
    public static final int MAX_ERROR_LENGTH = 500;

    @Column(name = "idempotency_key", nullable = false, length = 120, unique = true)
    private String idempotencyKey;

    @Column(name = "grant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID grantId;

    /** {@code SupportAccessNotificationKind} の名前。 */
    @Column(name = "notification_kind", nullable = false, length = 40)
    private String notificationKind;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

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

    /** 配送を担当する（リース）。リース中は他の配送役（即時・バッチ）が同じ行を取らない。 */
    public void lease(Instant leaseUntil) {
        this.attemptCount = attemptCount + 1;
        this.nextAttemptAt = leaseUntil;
    }

    public void markPublished(Instant now) {
        this.publishedAt = now;
        this.lastError = null;
    }

    /** 配送に失敗した。retryAt 以降に再試行バッチが拾う。 */
    public void markFailed(String error, Instant retryAt) {
        String summary = error == null ? "unknown" : error;
        this.lastError = summary.length() > MAX_ERROR_LENGTH ? summary.substring(0, MAX_ERROR_LENGTH) : summary;
        this.nextAttemptAt = retryAt;
    }
}
