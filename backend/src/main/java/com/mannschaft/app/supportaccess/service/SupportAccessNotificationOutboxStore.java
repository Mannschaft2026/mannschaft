package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.entity.SupportAccessNotificationOutboxEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessNotificationRelayRequestedEvent;
import com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F10.9 アプリ内通知 outbox の取得（リース）・確定・失敗の記録（supportaccess の Repository だけに触れる短い REQUIRES_NEW）。
 *
 * <p>通知の作成（notification ドメイン）はこのクラスの TX に入れない。{@code SupportAccessNotificationListener}
 * （AFTER_COMMIT）が「リース → 配送 → 確定」を TX の外で順に呼ぶ。リースは {@code FOR UPDATE SKIP LOCKED} で
 * 行を取り {@code next_attempt_at} を先送りし、即時配送と再試行バッチが同じ行を二重に取らないようにする。</p>
 */
@Component
public class SupportAccessNotificationOutboxStore {

    /** 取得した行を他の配送役から隠す時間（配送がこの時間内に終わらなければ再試行の対象に戻る）。 */
    static final Duration LEASE = Duration.ofMinutes(5);
    /** 再試行バッチの1回あたりの最大件数。 */
    public static final int RELAY_BATCH_LIMIT = 200;

    private final SupportAccessNotificationOutboxRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SupportAccessNotificationOutboxStore(SupportAccessNotificationOutboxRepository repository,
                                                ApplicationEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** 配送の対象（outbox 行の写し）。 */
    public record PendingNotification(UUID id, UUID grantId, String notificationKind, Long recipientUserId,
                                      int attemptCount) {
        static PendingNotification from(SupportAccessNotificationOutboxEntity e) {
            return new PendingNotification(e.getId(), e.getGrantId(), e.getNotificationKind(),
                    e.getRecipientUserId(), e.getAttemptCount());
        }
    }

    /** 指定 grant の未配送の行をリースして返す（遷移の直後の即時配送）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PendingNotification> leaseForGrant(UUID grantId) {
        Instant now = Instant.now(clock);
        return lease(repository.findDueByGrantIdForUpdate(grantId, now), now);
    }

    /** 未配送でリースの切れた行を最大 limit 件リースして返す（再試行）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PendingNotification> leaseDue(int limit) {
        Instant now = Instant.now(clock);
        return lease(repository.findDueForUpdate(now, PageRequest.of(0, limit)), now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID id) {
        repository.findById(id).ifPresent(row -> {
            row.markPublished(Instant.now(clock));
            repository.save(row);
        });
    }

    /** 配送に失敗した。試行回数に応じて間隔を空け（最大1時間）、再試行バッチに残す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String error) {
        repository.findById(id).ifPresent(row -> {
            row.markFailed(error, Instant.now(clock).plus(SupportAccessAuditOutboxStore.backoff(row.getAttemptCount())));
            repository.save(row);
        });
    }

    /**
     * 再試行バッチの入口: 未配送の行があれば、配送の依頼イベントをこの TX で publish する。
     * 配送（通知の作成）はコミット後の AFTER_COMMIT リスナーが行う（通知の発火を業務 TX の外へ出す正規形）。
     *
     * @return 未配送の件数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long requestRelayOfDue() {
        long pending = repository.countByPublishedAtIsNull();
        if (pending > 0) {
            eventPublisher.publishEvent(new SupportAccessNotificationRelayRequestedEvent(Instant.now(clock)));
        }
        return pending;
    }

    /** 未配送の件数（監視・テスト用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countUnpublished() {
        return repository.countByPublishedAtIsNull();
    }

    private List<PendingNotification> lease(List<SupportAccessNotificationOutboxEntity> rows, Instant now) {
        Instant leaseUntil = now.plus(LEASE);
        for (SupportAccessNotificationOutboxEntity row : rows) {
            row.lease(leaseUntil);
            repository.save(row);
        }
        return rows.stream().map(PendingNotification::from).toList();
    }
}
