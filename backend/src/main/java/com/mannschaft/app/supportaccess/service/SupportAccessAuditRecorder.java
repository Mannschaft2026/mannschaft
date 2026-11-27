package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore.PendingAudit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * F10.9 監査 outbox（support_access_audit_outbox）を audit_logs へ反映する（別 Bean・トランザクションを持たない）。
 *
 * <p><b>監査が消えない構成（検分差し戻し対応の設計判断）</b>:</p>
 * <ol>
 *   <li>遷移（依頼・許可・取消…）・拒否は、<b>遷移と同じトランザクション</b>で outbox に1行書く
 *       （{@link SupportAccessGrantTransitionService}・{@link SupportAccessLogRecorder#recordDenied}）。
 *       遷移が確定すれば監査の元も必ず確定し、遷移が巻き戻れば監査の元も残らない。</li>
 *   <li>コミット後、本クラスが outbox の行をリースし（{@link SupportAccessAuditOutboxStore}・REQUIRES_NEW）、
 *       {@link AuditLogService#recordStrict}（auth の別 Bean・REQUIRES_NEW・書込可能な接続・<b>失敗は例外</b>）で
 *       audit_logs に書き、outbox を確定する。</li>
 *   <li>反映に失敗したら、例外を握りつぶさず outbox に失敗（試行回数・要約）を記録し、再試行バッチ
 *       （{@code SupportAccessBatchService#relayPendingAudits}）が間隔を空けて拾い直す。</li>
 * </ol>
 *
 * <p>本クラスは {@code @Transactional} を持たない。supportaccess の TX の中から auth の Repository へ到達させない
 * ため（D-3T）。audit_logs の書込と outbox の確定は別 TX なので、反映は少なくとも1回（at-least-once）で、
 * 確定の直前に落ちたときだけ重複しうる。重複は metadata の {@code auditOutboxId} で同一の事実と分かる。</p>
 *
 * <p>actor（userId）は運営者、target は本人、metadata に grantId を入れる。種別名は
 * {@code SUPPORT_ACCESS_} + {@code SupportAccessAuditAction}（{@code AuditEventType} に同名で定義）。</p>
 */
@Slf4j
@Component
public class SupportAccessAuditRecorder {

    static final String EVENT_PREFIX = "SUPPORT_ACCESS_";

    /** 再試行で一度に反映する上限。 */
    public static final int RELAY_BATCH_LIMIT = 200;

    /** この回数を超えて失敗し続ける行は運用の対処が要る（ERROR で知らせる）。 */
    static final int ALERT_ATTEMPTS = 5;

    private final AuditLogService auditLogService;
    private final SupportAccessAuditOutboxStore outboxStore;

    public SupportAccessAuditRecorder(AuditLogService auditLogService, SupportAccessAuditOutboxStore outboxStore) {
        this.auditLogService = auditLogService;
        this.outboxStore = outboxStore;
    }

    /**
     * 遷移のコミット直後に、その grant の未反映の監査を反映する。反映できた件数を返す。
     *
     * <p>遷移は既に確定しているため、ここでの失敗（DB 障害でリース自体ができない等）を利用者への失敗にはしない。
     * 監査の元は outbox に確定済みで、再試行バッチが必ず拾う（失敗は ERROR で残す）。</p>
     */
    public int relayForGrant(UUID grantId) {
        if (grantId == null) {
            return 0;
        }
        List<PendingAudit> pending;
        try {
            pending = outboxStore.leaseForGrant(grantId);
        } catch (RuntimeException e) {
            log.error("サポート閲覧の監査の即時反映を開始できない（outbox に残り再試行バッチが反映する）: grantId={}",
                    grantId, e);
            return 0;
        }
        return relay(pending);
    }

    /** 未反映でリースの切れた監査を最大 {@link #RELAY_BATCH_LIMIT} 件反映する（再試行バッチ・拒否の監査）。 */
    public int relayDue() {
        return relay(outboxStore.leaseDue(RELAY_BATCH_LIMIT));
    }

    private int relay(List<PendingAudit> pending) {
        int published = 0;
        for (PendingAudit audit : pending) {
            try {
                auditLogService.recordStrict(audit.eventType(), audit.operatorUserId(), audit.targetUserId(),
                        metadata(audit));
            } catch (RuntimeException e) {
                // 握りつぶさない: 失敗を outbox に残し、再試行バッチが拾い直す（監査の事実は outbox に残っている）
                outboxStore.markFailed(audit.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
                if (audit.attemptCount() >= ALERT_ATTEMPTS) {
                    log.error("サポート閲覧の監査の反映が繰り返し失敗（要対処）: outboxId={}, eventType={}, attempts={}",
                            audit.id(), audit.eventType(), audit.attemptCount(), e);
                } else {
                    log.warn("サポート閲覧の監査の反映に失敗（再試行する）: outboxId={}, eventType={}, attempts={}, error={}",
                            audit.id(), audit.eventType(), audit.attemptCount(), e.toString());
                }
                continue;
            }
            try {
                outboxStore.markPublished(audit.id());
            } catch (RuntimeException e) {
                // audit_logs には書けている。確定できなかった行はリース切れで再試行され、重複は auditOutboxId で分かる
                log.error("サポート閲覧の監査の反映済みの確定に失敗（再試行で重複しうる）: outboxId={}", audit.id(), e);
            }
            published++;
        }
        return published;
    }

    private static String metadata(PendingAudit audit) {
        StringBuilder sb = new StringBuilder("{");
        if (audit.grantId() != null) {
            sb.append("\"grantId\":\"").append(audit.grantId()).append("\",");
        }
        sb.append("\"auditOutboxId\":\"").append(audit.id()).append("\"");
        if (audit.occurredAt() != null) {
            sb.append(",\"occurredAt\":\"").append(audit.occurredAt()).append("\"");
        }
        return sb.append('}').toString();
    }
}
