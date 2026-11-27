package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
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

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessLogRepository logRepository;
    private final Clock clock;

    public SupportAccessRetentionService(SupportAccessGrantRepository grantRepository,
                                         SupportAccessLogRepository logRepository,
                                         Clock clock) {
        this.grantRepository = grantRepository;
        this.logRepository = logRepository;
        this.clock = clock;
    }

    /** 日次バッチ: 閲覧記録は1年、終了した依頼・許可は3年を過ぎたら物理削除する。 */
    public void purgeExpiredRecords() {
        Instant now = Instant.now(clock);
        int logs = logRepository.deleteByOccurredAtBefore(now.atZone(java.time.ZoneOffset.UTC).minusYears(LOG_RETENTION_YEARS).toInstant());
        int grants = grantRepository.deleteByStatusInAndUpdatedAtBefore(ENDED_STATUSES, now.atZone(java.time.ZoneOffset.UTC).minusYears(GRANT_RETENTION_YEARS).toInstant());
        log.info("サポート閲覧の保存期間削除: 閲覧記録={}件, 依頼・許可={}件", logs, grants);
    }

    /** AccountPurgeService（強匿名化）の supportaccess ドメイン分: 本人が target の logs → grants の順に消す。 */
    public void purgeUser(Long targetUserId) {
        logRepository.deleteByTargetUserId(targetUserId);
        grantRepository.deleteByTargetUserId(targetUserId);
    }
}
