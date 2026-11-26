package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.SupportAccessEndedReason;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F10.9 依頼〜失効の状態遷移（運営側・本人側の両 API の業務処理）。
 *
 * <p>遷移ごとに {@link SupportAccessAuditRecorder}（REQUIRES_NEW）で監査し、
 * {@link SupportAccessGrantChangedEvent} を publish する（AFTER_COMMIT で通知）。
 * 状態は Entity をその場で書き換える（JPA の dirty checking）。</p>
 *
 * <p>他のドメインの Repository・Entity には触れず、利用者・所属・再認証は port 経由で参照する。</p>
 */
@Slf4j
@Service
@Transactional
public class SupportAccessGrantService {

    /** 依頼の有効期間（72時間）。 */
    public static final long REQUEST_TTL_HOURS = 72;
    /** 許可の有効期間（24時間固定・延長不可）。 */
    public static final long GRANT_TTL_HOURS = 24;
    /** 運営者ごとの1日の依頼上限。 */
    public static final int DAILY_REQUEST_LIMIT = 20;

    static final int REASON_MIN_LENGTH = 10;
    static final int REASON_MAX_LENGTH = 1000;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final Set<SupportAccessStatus> OPEN_STATUSES =
            EnumSet.of(SupportAccessStatus.PENDING, SupportAccessStatus.APPROVED);

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
                                     @Qualifier("wallClock") Clock clock) {
        this.grantRepository = grantRepository;
        this.userDirectory = userDirectory;
        this.affiliationPort = affiliationPort;
        this.reauthenticationVerifier = reauthenticationVerifier;
        this.auditRecorder = auditRecorder;
        this.logRecorder = logRecorder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ─── 運営側 ────────────────────────────────────────────

    /** 運営者がサポート閲覧を依頼する（PENDING・72時間で失効）。 */
    public SupportAccessGrantEntity request(Long operatorUserId, CreateSupportAccessGrantRequest request) {
        String reason = normalizeReason(request.reason());
        LocalDateTime now = LocalDateTime.now(clock);
        Long targetUserId = request.targetUserId();

        if (affiliationPort.isAffiliatedWithAnyTenant(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATOR_AFFILIATED);
        }
        validateTarget(operatorUserId, targetUserId);

        // 開いている grant が期限切れ（バッチ前）なら、その場で失効してから新しい依頼を作る
        grantRepository.findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(operatorUserId, targetUserId, OPEN_STATUSES)
                .ifPresent(open -> {
                    if (!isOverdue(open, now)) {
                        throw new BusinessException(SupportAccessErrorCode.DUPLICATE_OPEN_GRANT);
                    }
                    expireNow(open);
                    // 同じ組合せの一意制約（open_flag）に当たらないよう、失効を先に確定する
                    grantRepository.saveAndFlush(open);
                });

        if (grantRepository.countByOperatorUserIdAndRequestedAtAfter(operatorUserId, now.minusDays(1))
                >= DAILY_REQUEST_LIMIT) {
            throw new BusinessException(SupportAccessErrorCode.REQUEST_RATE_LIMITED);
        }

        Long guardianUserId = userDirectory.findRequiredGuardianUserId(targetUserId).orElse(null);
        SupportAccessGrantEntity grant = SupportAccessGrantEntity.builder()
                .operatorUserId(operatorUserId)
                .targetUserId(targetUserId)
                .status(SupportAccessStatus.PENDING)
                .requestReason(reason)
                .referenceType(request.referenceType())
                .referenceId(request.referenceId())
                .requestedAt(now)
                .requestExpiresAt(now.plusHours(REQUEST_TTL_HOURS))
                .guardianApprovalRequired(guardianUserId != null)
                .guardianUserId(guardianUserId)
                .build();
        SupportAccessGrantEntity saved;
        try {
            saved = grantRepository.save(grant);
            grantRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 同時依頼の競合（open_flag の一意制約）
            throw new BusinessException(SupportAccessErrorCode.DUPLICATE_OPEN_GRANT, e);
        }

        auditRecorder.record(SupportAccessAuditAction.REQUESTED, operatorUserId, targetUserId, saved.getId());
        publish(SupportAccessAuditAction.REQUESTED, saved);
        return saved;
    }

    /** 運営者が依頼・許可を撤回する（CANCELLED）。 */
    public SupportAccessGrantEntity cancel(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        requireOpen(grant);
        grant.cancel(LocalDateTime.now(clock));
        grantRepository.save(grant);
        auditRecorder.record(SupportAccessAuditAction.CANCELLED, grant.getOperatorUserId(), grant.getTargetUserId(), grantId);
        publish(SupportAccessAuditAction.CANCELLED, grant);
        return grant;
    }

    /** 閲覧開始。承認済みで期限内の grant だけが開始でき、以後のヘッダー付きリクエストが有効になる。 */
    public SupportAccessSessionResponse startSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!isUsable(grant, now)) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }
        if (affiliationPort.isAffiliatedWithAnyTenant(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATOR_AFFILIATED);
        }
        SupportAccessUserSnapshot target = userDirectory.findUser(grant.getTargetUserId())
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE));
        if (!STATUS_ACTIVE.equals(target.status()) || target.withdrawalPending() || target.systemAdmin()) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }

        grant.startSession(now);
        grantRepository.save(grant);
        SupportAccessContext context = contextOf(grant);
        logRecorder.recordSessionStart(context);
        auditRecorder.record(SupportAccessAuditAction.SESSION_STARTED, operatorUserId, grant.getTargetUserId(), grantId);
        publish(SupportAccessAuditAction.SESSION_STARTED, grant);
        return new SupportAccessSessionResponse(grantId, grant.getTargetUserId(), target.displayName(), grant.getExpiresAt());
    }

    /** 閲覧終了。許可自体は期限まで残るが、ヘッダーは次のリクエストから拒否される。 */
    public void endSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        if (grant.getActiveSessionStartedAt() == null) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
        if (!grant.isSessionActive()) {
            return; // 終了済み（冪等）
        }
        grant.endSession(LocalDateTime.now(clock));
        grantRepository.save(grant);
        logRecorder.recordSessionEnd(contextOf(grant));
        auditRecorder.record(SupportAccessAuditAction.SESSION_ENDED, operatorUserId, grant.getTargetUserId(), grantId);
        publish(SupportAccessAuditAction.SESSION_ENDED, grant);
    }

    // ─── 本人・保護者側 ────────────────────────────────────

    /** 本人または（未成年・後見下の場合）保護者の許可。両方そろって初めて APPROVED（期限は最後の許可+24h）。 */
    public SupportAccessGrantEntity approve(Long userId, UUID grantId, SupportAccessApproveRequest request) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        boolean asGuardian = isGuardianOnly(grant, userId);
        LocalDateTime now = LocalDateTime.now(clock);

        if (grant.getStatus() != SupportAccessStatus.PENDING
                || !now.isBefore(grant.getRequestExpiresAt())
                || grant.hasApprovalBy(asGuardian)) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
        requireTargetEligible(grant.getTargetUserId());
        verifyReauthentication(userId, request);

        if (asGuardian) {
            grant.recordGuardianApproval(now, GRANT_TTL_HOURS);
        } else {
            grant.recordTargetApproval(now, GRANT_TTL_HOURS);
        }
        grantRepository.save(grant);
        if (grant.getStatus() == SupportAccessStatus.APPROVED) {
            auditRecorder.record(SupportAccessAuditAction.APPROVED, grant.getOperatorUserId(), grant.getTargetUserId(), grantId);
            publish(SupportAccessAuditAction.APPROVED, grant);
        } else {
            log.info("サポート閲覧: 片側の許可を記録（もう一方の許可待ち）grantId={}", grantId);
        }
        return grant;
    }

    /** 本人（または保護者）が拒否する（DENIED）。 */
    public SupportAccessGrantEntity deny(Long userId, UUID grantId, String comment) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (grant.getStatus() != SupportAccessStatus.PENDING || !now.isBefore(grant.getRequestExpiresAt())) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
        grant.deny(now, comment);
        grantRepository.save(grant);
        auditRecorder.record(SupportAccessAuditAction.DENIED, grant.getOperatorUserId(), grant.getTargetUserId(), grantId);
        publish(SupportAccessAuditAction.DENIED, grant);
        return grant;
    }

    /** 本人（または保護者）はいつでも許可を取り消せる（REVOKED）。次のリクエストから即座に拒否される。 */
    public SupportAccessGrantEntity revoke(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        requireOpen(grant);
        grant.revoke(LocalDateTime.now(clock), SupportAccessEndedReason.USER_REVOKED);
        grantRepository.save(grant);
        auditRecorder.record(SupportAccessAuditAction.REVOKED, grant.getOperatorUserId(), grant.getTargetUserId(), grantId);
        publish(SupportAccessAuditAction.REVOKED, grant);
        return grant;
    }

    // ─── 退会・失効 ────────────────────────────────────────

    /**
     * 退会申請（WithdrawalRequestedEvent）で本人の開いている grant を即座に REVOKED(WITHDRAWAL) にする。
     * 呼び出し元のトランザクション（または独立した新規）に関わらず確実に確定させるため REQUIRES_NEW。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForWithdrawal(Long targetUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SupportAccessGrantEntity> open = grantRepository.findByTargetUserIdAndStatusIn(targetUserId, OPEN_STATUSES);
        for (SupportAccessGrantEntity grant : open) {
            grant.revoke(now, SupportAccessEndedReason.WITHDRAWAL);
            grantRepository.save(grant);
            auditRecorder.record(SupportAccessAuditAction.REVOKED, grant.getOperatorUserId(), grant.getTargetUserId(), grant.getId());
            publish(SupportAccessAuditAction.REVOKED, grant);
        }
        return open.size();
    }

    /** 5分ごとのバッチ: PENDING(依頼72h)・APPROVED(許可24h) の期限切れを EXPIRED にする。 */
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        int count = 0;
        for (SupportAccessGrantEntity grant
                : grantRepository.findByStatusAndRequestExpiresAtBefore(SupportAccessStatus.PENDING, now)) {
            expireNow(grant);
            grantRepository.save(grant);
            count++;
        }
        for (SupportAccessGrantEntity grant
                : grantRepository.findByStatusAndExpiresAtBefore(SupportAccessStatus.APPROVED, now)) {
            expireNow(grant);
            grantRepository.save(grant);
            count++;
        }
        return count;
    }

    // ─── 内部 ──────────────────────────────────────────────

    private void expireNow(SupportAccessGrantEntity grant) {
        grant.expire();
        auditRecorder.record(SupportAccessAuditAction.EXPIRED, grant.getOperatorUserId(), grant.getTargetUserId(), grant.getId());
    }

    /** 開いている grant が期限切れか（PENDING は依頼期限、APPROVED は許可期限）。 */
    private static boolean isOverdue(SupportAccessGrantEntity grant, LocalDateTime now) {
        if (grant.getStatus() == SupportAccessStatus.PENDING) {
            return !now.isBefore(grant.getRequestExpiresAt());
        }
        return grant.getExpiresAt() != null && !now.isBefore(grant.getExpiresAt());
    }

    /** 承認済み・本人（と必要なら保護者）の許可が揃い・取消なし・期限内か。 */
    private static boolean isUsable(SupportAccessGrantEntity grant, LocalDateTime now) {
        return grant.getStatus() == SupportAccessStatus.APPROVED
                && grant.getRevokedAt() == null
                && grant.getExpiresAt() != null
                && now.isBefore(grant.getExpiresAt())
                && grant.getDecidedAt() != null
                && (!grant.isGuardianApprovalRequired() || grant.getGuardianDecidedAt() != null);
    }

    private static void requireOpen(SupportAccessGrantEntity grant) {
        if (!grant.isOpen()) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
    }

    private static SupportAccessContext contextOf(SupportAccessGrantEntity grant) {
        return new SupportAccessContext(grant.getId(), grant.getOperatorUserId(), grant.getTargetUserId(), grant.getExpiresAt());
    }

    private void publish(SupportAccessAuditAction action, SupportAccessGrantEntity grant) {
        eventPublisher.publishEvent(new SupportAccessGrantChangedEvent(action, grant.getId(),
                grant.getOperatorUserId(), grant.getTargetUserId(), grant.getGuardianUserId()));
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            throw new BusinessException(SupportAccessErrorCode.REASON_INVALID);
        }
        String stripped = reason.strip();
        if (stripped.length() < REASON_MIN_LENGTH || stripped.length() > REASON_MAX_LENGTH) {
            throw new BusinessException(SupportAccessErrorCode.REASON_INVALID);
        }
        return stripped;
    }

    /** 対象が依頼できる相手か（自分・運営アカウント・退会手続中・ACTIVE 以外は 005）。 */
    private void validateTarget(Long operatorUserId, Long targetUserId) {
        if (targetUserId == null || targetUserId.equals(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
        }
        SupportAccessUserSnapshot target = userDirectory.findUser(targetUserId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.TARGET_INVALID));
        if (target.systemAdmin() || target.withdrawalPending() || !STATUS_ACTIVE.equals(target.status())) {
            throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
        }
    }

    /** 許可の時点で本人が ACTIVE であること（保護者同意待ち・凍結・退会手続中の間は許可できない）。 */
    private void requireTargetEligible(Long targetUserId) {
        SupportAccessUserSnapshot target = userDirectory.findUser(targetUserId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.TARGET_INVALID));
        if (!STATUS_ACTIVE.equals(target.status()) || target.withdrawalPending()) {
            throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
        }
    }

    /** 再認証（パスワード／パスキー）。材料が無い・失敗は 013。メールのワンクリック許可は成立しない。 */
    private void verifyReauthentication(Long userId, SupportAccessApproveRequest request) {
        boolean hasMaterial = request != null
                && ((request.currentPassword() != null && !request.currentPassword().isEmpty())
                || (request.passkeyAssertionJson() != null && !request.passkeyAssertionJson().isBlank()));
        if (!hasMaterial || !reauthenticationVerifier.verify(userId, request)) {
            throw new BusinessException(SupportAccessErrorCode.REAUTH_FAILED);
        }
    }

    private SupportAccessGrantEntity findOwnedByOperator(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        if (!grant.getOperatorUserId().equals(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND);
        }
        return grant;
    }

    /** 本人、または保護者の承認が要る grant の保護者だけが操作できる。他人には存在を明かさない（001）。 */
    private SupportAccessGrantEntity findVisibleToUser(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        boolean isTarget = grant.getTargetUserId().equals(userId);
        boolean isGuardian = grant.isGuardianApprovalRequired() && userId.equals(grant.getGuardianUserId());
        if (!isTarget && !isGuardian) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND);
        }
        return grant;
    }

    /** 操作者が保護者としての立場か（本人でもある場合は本人として扱う）。 */
    private static boolean isGuardianOnly(SupportAccessGrantEntity grant, Long userId) {
        return !grant.getTargetUserId().equals(userId)
                && grant.isGuardianApprovalRequired() && userId.equals(grant.getGuardianUserId());
    }
}
