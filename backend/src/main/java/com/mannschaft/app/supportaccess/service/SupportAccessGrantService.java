package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.UUID;

/**
 * F10.9 依頼〜失効の状態遷移（運営側・本人側の両 API の業務処理）。試練スタブ。
 *
 * <p>遷移ごとに {@link SupportAccessAuditRecorder}（REQUIRES_NEW）で監査し、
 * {@code SupportAccessGrantChangedEvent} を publish する（AFTER_COMMIT で通知）。</p>
 */
public class SupportAccessGrantService {

    /** 依頼の有効期間（72時間）。 */
    public static final long REQUEST_TTL_HOURS = 72;
    /** 許可の有効期間（24時間固定・延長不可）。 */
    public static final long GRANT_TTL_HOURS = 24;
    /** 運営者ごとの1日の依頼上限。 */
    public static final int DAILY_REQUEST_LIMIT = 20;

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;
    private final SupportAccessOperatorAffiliationPort affiliationPort;
    private final SupportAccessReauthenticationVerifier reauthenticationVerifier;
    private final SupportAccessAuditRecorder auditRecorder;
    private final SupportAccessLogRecorder logRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SupportAccessGrantService(SupportAccessGrantRepository grantRepository,
                                     SupportAccessUserDirectory userDirectory,
                                     SupportAccessOperatorAffiliationPort affiliationPort,
                                     SupportAccessReauthenticationVerifier reauthenticationVerifier,
                                     SupportAccessAuditRecorder auditRecorder,
                                     SupportAccessLogRecorder logRecorder,
                                     ApplicationEventPublisher eventPublisher,
                                     Clock clock) {
        this.grantRepository = grantRepository;
        this.userDirectory = userDirectory;
        this.affiliationPort = affiliationPort;
        this.reauthenticationVerifier = reauthenticationVerifier;
        this.auditRecorder = auditRecorder;
        this.logRecorder = logRecorder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public SupportAccessGrantEntity request(Long operatorUserId, CreateSupportAccessGrantRequest request) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public SupportAccessGrantEntity cancel(Long operatorUserId, UUID grantId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public SupportAccessSessionResponse startSession(Long operatorUserId, UUID grantId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public void endSession(Long operatorUserId, UUID grantId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** 本人または（未成年・後見下の場合）保護者の許可。両方そろって初めて APPROVED（期限は最後の許可+24h）。 */
    public SupportAccessGrantEntity approve(Long userId, UUID grantId, SupportAccessApproveRequest request) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public SupportAccessGrantEntity deny(Long userId, UUID grantId, String comment) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public SupportAccessGrantEntity revoke(Long userId, UUID grantId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** 退会申請（WithdrawalRequestedEvent）で本人の開いている grant を即座に REVOKED(WITHDRAWAL) にする。 */
    public int revokeAllForWithdrawal(Long targetUserId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** 5分ごとのバッチ: PENDING(依頼72h)・APPROVED(許可24h) の期限切れを EXPIRED にする。 */
    public int expireOverdue() {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
