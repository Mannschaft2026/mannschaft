package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * F10.9 保存期間（依頼・許可3年、閲覧記録1年）を過ぎた行の日次物理削除と、退会30日後の本人分の削除。
 *
 * <p>閲覧記録は grant への FK を持つため、常に logs → grants の順で消す。</p>
 */
@Slf4j
@Service
@Transactional
public class SupportAccessRetentionService {

    /** 閲覧記録の保存年数。 */
    static final int LOG_RETENTION_YEARS = 1;
    /** 終了した依頼・許可の保存年数。 */
    static final int GRANT_RETENTION_YEARS = 3;

    /** 保存期間の対象にする終了済みの状態（PENDING/APPROVED は開いているので消さない）。 */
    private static final List<SupportAccessStatus> ENDED_STATUSES = List.of(
            SupportAccessStatus.DENIED, SupportAccessStatus.REVOKED,
            SupportAccessStatus.EXPIRED, SupportAccessStatus.CANCELLED);

    /** audit_logs へ反映済みの監査 outbox を残す日数（反映済みの事実は audit_logs が持つ）。 */
    static final int PUBLISHED_OUTBOX_RETENTION_DAYS = 30;

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessLogRepository logRepository;
    private final SupportAccessAuditOutboxRepository auditOutboxRepository;
    private final SupportAccessNotificationOutboxRepository notificationOutboxRepository;
    private final Clock clock;

    public SupportAccessRetentionService(SupportAccessGrantRepository grantRepository,
                                         SupportAccessLogRepository logRepository,
                                         SupportAccessAuditOutboxRepository auditOutboxRepository,
                                         SupportAccessNotificationOutboxRepository notificationOutboxRepository,
                                         Clock clock) {
        this.grantRepository = grantRepository;
        this.logRepository = logRepository;
        this.auditOutboxRepository = auditOutboxRepository;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.clock = clock;
    }

    /** 日次バッチ: 閲覧記録は1年、終了した依頼・許可は3年を過ぎたら物理削除する。 */
    public void purgeExpiredRecords() {
        Instant now = Instant.now(clock);
        int logs = logRepository.deleteByOccurredAtBefore(now.atZone(java.time.ZoneOffset.UTC).minusYears(LOG_RETENTION_YEARS).toInstant());
        int grants = grantRepository.deleteByStatusInAndUpdatedAtBefore(ENDED_STATUSES, now.atZone(java.time.ZoneOffset.UTC).minusYears(GRANT_RETENTION_YEARS).toInstant());
        Instant outboxThreshold = now.minus(java.time.Duration.ofDays(PUBLISHED_OUTBOX_RETENTION_DAYS));
        int outbox = auditOutboxRepository.deletePublishedBefore(outboxThreshold);
        int notifications = notificationOutboxRepository.deletePublishedBefore(outboxThreshold);
        log.info("サポート閲覧の保存期間削除: 閲覧記録={}件, 依頼・許可={}件, 反映済みの監査 outbox={}件, 配送済みの通知 outbox={}件",
                logs, grants, outbox, notifications);
    }

    /**
     * AccountPurgeService（強匿名化）の supportaccess ドメイン分。
     * <ul>
     *   <li>本人が target の logs → grants の順に消す（logs は grants への FK を持つ）。本人宛の監査 outbox は
     *       audit_logs へ反映済みの行だけ消し、<b>未反映の行は本人の userId を外して残す</b>（第2回検分: 未反映まで消すと
     *       代理閲覧の監査が audit_logs に届かないまま失われる。反映後は保存期間の削除が消す）。本人宛・本人に関わる
     *       通知 outbox は消す（本文を持たず、宛先が居ない通知は配送しない）。</li>
     *   <li>保護者としてだけ記録されている行（他人が target の grant の guardian_user_id）は、子の記録を残したまま
     *       保護者の userId を外す（子の閲覧記録まで消さない）。</li>
     *   <li>運営者としての依頼ロック行を消す（grants・logs の operator_user_id は ID 参照のまま残す方針）。</li>
     * </ul>
     */
    public void purgeUser(Long userId) {
        logRepository.deleteByTargetUserId(userId);
        auditOutboxRepository.deletePublishedByTargetUserId(userId);
        auditOutboxRepository.anonymizeUnpublishedByTargetUserId(userId);
        notificationOutboxRepository.deleteByUserId(userId);
        grantRepository.deleteByTargetUserId(userId);
        grantRepository.clearGuardianUserId(userId);
        grantRepository.deleteRequestLock(userId);
    }
}
