package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;

import java.time.Clock;

/** F10.9 保存期間（依頼・許可3年、閲覧記録1年）を過ぎた行の日次物理削除と、退会30日後の本人分の削除。試練スタブ。 */
public class SupportAccessRetentionService {

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessLogRepository logRepository;
    private final Clock clock;

    public SupportAccessRetentionService(SupportAccessGrantRepository grantRepository,
                                         SupportAccessLogRepository logRepository, Clock clock) {
        this.grantRepository = grantRepository;
        this.logRepository = logRepository;
        this.clock = clock;
    }

    public void purgeExpiredRecords() {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** AccountPurgeService（強匿名化）の supportaccess ドメイン分: 本人が target の logs → grants の順に消す。 */
    public void purgeUser(Long targetUserId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
