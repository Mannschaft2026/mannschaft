package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import com.mannschaft.app.supportaccess.entity.SupportAccessAuditOutboxEntity;
import com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository;
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
 * F10.9 監査 outbox の取得（リース）・確定・失敗の記録（supportaccess の Repository だけに触れる短い REQUIRES_NEW）。
 *
 * <p>audit_logs（auth ドメイン）への書込はこのクラスの TX に入れない（D-3T）。{@link SupportAccessAuditRecorder} が
 * 「リース → audit_logs へ書く → 確定」を TX の外で順に呼ぶ。リースは行ロックを取って
 * {@code next_attempt_at} を先送りし、即時反映と再試行バッチが同じ行を二重に取らないようにする。
 * 代理閲覧中でも書けるよう {@link SupportAccessWritable} を付ける（拒否の監査は代理ヘッダー付きのリクエストで起きる）。</p>
 */
@Component
@SupportAccessWritable
public class SupportAccessAuditOutboxStore {

    /** 取得した行を他の反映役から隠す時間（反映がこの時間内に終わらなければ再試行の対象に戻る）。 */
    static final Duration LEASE = Duration.ofMinutes(5);
    /** 失敗時の再試行間隔の上限。 */
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final SupportAccessAuditOutboxRepository repository;
    private final Clock clock;

    public SupportAccessAuditOutboxStore(SupportAccessAuditOutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 反映の対象（outbox 行の写し）。 */
    public record PendingAudit(UUID id, UUID grantId, String eventType, Long operatorUserId, Long targetUserId,
                               Instant occurredAt, int attemptCount) {
        static PendingAudit from(SupportAccessAuditOutboxEntity e) {
            return new PendingAudit(e.getId(), e.getGrantId(), e.getEventType(), e.getOperatorUserId(),
                    e.getTargetUserId(), e.getOccurredAt(), e.getAttemptCount());
        }
    }

    /** 指定 grant の未反映の行をリースして返す（遷移の直後の即時反映）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PendingAudit> leaseForGrant(UUID grantId) {
        Instant now = Instant.now(clock);
        return lease(repository.findDueByGrantIdForUpdate(grantId, now), now);
    }

    /** 指定の1行が未反映ならリースして返す（拒否の監査の即時反映。他の反映役が掴んでいれば飛ばす）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PendingAudit> leaseById(UUID outboxId) {
        Instant now = Instant.now(clock);
        return lease(repository.findDueByIdForUpdate(outboxId, now), now);
    }

    /** 未反映でリースの切れた行を最大 limit 件リースして返す（再試行バッチ）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<PendingAudit> leaseDue(int limit) {
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

    /** 反映に失敗した。試行回数に応じて間隔を空け（最大1時間）、再試行バッチに残す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String error) {
        repository.findById(id).ifPresent(row -> {
            row.markFailed(error, Instant.now(clock).plus(backoff(row.getAttemptCount())));
            repository.save(row);
        });
    }

    /** 未反映の件数（監視・テスト用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countUnpublished() {
        return repository.countByPublishedAtIsNull();
    }

    static Duration backoff(int attemptCount) {
        long minutes = 1L << Math.min(Math.max(attemptCount - 1, 0), 6);
        Duration d = Duration.ofMinutes(minutes);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private List<PendingAudit> lease(List<SupportAccessAuditOutboxEntity> rows, Instant now) {
        Instant leaseUntil = now.plus(LEASE);
        for (SupportAccessAuditOutboxEntity row : rows) {
            row.lease(leaseUntil);
            repository.save(row);
        }
        return rows.stream().map(PendingAudit::from).toList();
    }
}
