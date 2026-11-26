package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * F10.9 代理ヘッダーの毎リクエスト検証（キャッシュしない。取消直後の次リクエストから拒否）。試練スタブ。
 *
 * <p>違反は BusinessException: 001(404 無い/他の運営者)・002(403 無効/未開始/本人の状態)・010(403 運営者が所属)。</p>
 */
public class SupportAccessGrantValidator {

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;
    private final SupportAccessOperatorAffiliationPort affiliationPort;
    private final Clock clock;

    public SupportAccessGrantValidator(SupportAccessGrantRepository grantRepository,
                                       SupportAccessUserDirectory userDirectory,
                                       SupportAccessOperatorAffiliationPort affiliationPort,
                                       Clock clock) {
        this.grantRepository = grantRepository;
        this.userDirectory = userDirectory;
        this.affiliationPort = affiliationPort;
        this.clock = clock;
    }

    public SupportAccessContext validate(Long operatorUserId, UUID grantId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
