package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.SupportAccessEndedReason;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessTransitionResult;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F10.9 状態遷移の書込トランザクション（supportaccess ドメインの Repository だけに触れる）。
 *
 * <p>他ドメイン（本人の状態・所属・保護者・再認証）への問い合わせと監査の記録は、このクラスの外
 * （{@link SupportAccessGrantService}・トランザクション開始前／コミット後）で行う。CLAUDE.md の
 * 「{@code @Transactional} はドメイン内に閉じる」に従い、port 実装経由で他ドメインの Repository に
 * 到達するトランザクションを作らないための分割。値は {@link SupportAccessGrantSnapshot} で返し、
 * Entity は公開しない。状態は Entity をその場で書き換える（JPA の dirty checking）。</p>
 *
 * <p>各メソッドは、トランザクション開始前に済ませた判定の結果を信用せず、状態の検証をやり直す
 * （判定から書込までの間の競合に備える）。</p>
 */
@Slf4j
@Service
@Transactional
public class SupportAccessGrantTransitionService {

    private static final Set<SupportAccessStatus> OPEN_STATUSES =
            EnumSet.of(SupportAccessStatus.PENDING, SupportAccessStatus.APPROVED);

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessLogRecorder logRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SupportAccessGrantTransitionService(SupportAccessGrantRepository grantRepository,
                                               SupportAccessLogRecorder logRecorder,
                                               ApplicationEventPublisher eventPublisher,
                                               @Qualifier("wallClock") Clock clock) {
        this.grantRepository = grantRepository;
        this.logRecorder = logRecorder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ─── 運営側 ────────────────────────────────────────────

    /** 依頼を作る。guardianUserId は呼び出し側が TX の外で解決した値（null なら保護者の承認は不要）。 */
    public SupportAccessTransitionResult request(Long operatorUserId, CreateSupportAccessGrantRequest request,
                                                 String reason, Long guardianUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long targetUserId = request.targetUserId();
        List<SupportAccessGrantSnapshot> expired = new ArrayList<>();

        // 開いている grant が期限切れ（バッチ前）なら、その場で失効してから新しい依頼を作る
        grantRepository.findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(operatorUserId, targetUserId, OPEN_STATUSES)
                .ifPresent(open -> {
                    if (!isOverdue(open, now)) {
                        throw new BusinessException(SupportAccessErrorCode.DUPLICATE_OPEN_GRANT);
                    }
                    open.expire();
                    // 同じ組合せの一意制約（open_flag）に当たらないよう、失効を先に確定する
                    grantRepository.saveAndFlush(open);
                    expired.add(SupportAccessGrantSnapshot.from(open));
                });

        if (grantRepository.countByOperatorUserIdAndRequestedAtAfter(operatorUserId, now.minusDays(1))
                >= SupportAccessGrantService.DAILY_REQUEST_LIMIT) {
            throw new BusinessException(SupportAccessErrorCode.REQUEST_RATE_LIMITED);
        }

        SupportAccessGrantEntity grant = SupportAccessGrantEntity.builder()
                .operatorUserId(operatorUserId)
                .targetUserId(targetUserId)
                .status(SupportAccessStatus.PENDING)
                .requestReason(reason)
                .referenceType(request.referenceType())
                .referenceId(request.referenceId())
                .requestedAt(now)
                .requestExpiresAt(now.plusHours(SupportAccessGrantService.REQUEST_TTL_HOURS))
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
        publish(SupportAccessAuditAction.REQUESTED, saved);
        return new SupportAccessTransitionResult(SupportAccessGrantSnapshot.from(saved), expired);
    }

    /** 運営者が依頼・許可を撤回する（CANCELLED）。 */
    public SupportAccessTransitionResult cancel(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        requireOpen(grant);
        grant.cancel(LocalDateTime.now(clock));
        grantRepository.save(grant);
        publish(SupportAccessAuditAction.CANCELLED, grant);
        return result(grant);
    }

    /** 閲覧開始の前提（自分の grant・承認済みで期限内）の検証。対象本人の userId を返す。 */
    @Transactional(readOnly = true)
    public Long preflightStartSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        requireUsable(grant, LocalDateTime.now(clock));
        return grant.getTargetUserId();
    }

    /** 閲覧開始。承認済みで期限内の grant だけが開始できる（所属・本人の状態は呼び出し側が TX の前に確認済み）。 */
    public SupportAccessSessionResponse startSession(Long operatorUserId, UUID grantId, String targetDisplayName) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        LocalDateTime now = LocalDateTime.now(clock);
        requireUsable(grant, now);

        grant.startSession(now);
        grantRepository.save(grant);
        logRecorder.recordSessionStart(contextOf(grant));
        publish(SupportAccessAuditAction.SESSION_STARTED, grant);
        return new SupportAccessSessionResponse(grantId, grant.getTargetUserId(), targetDisplayName, grant.getExpiresAt());
    }

    /** 閲覧終了。終了済みなら何もせず null（冪等）、終了したら対象本人の userId を返す。 */
    public Long endSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        if (grant.getActiveSessionStartedAt() == null) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
        if (!grant.isSessionActive()) {
            return null;
        }
        grant.endSession(LocalDateTime.now(clock));
        grantRepository.save(grant);
        logRecorder.recordSessionEnd(contextOf(grant));
        publish(SupportAccessAuditAction.SESSION_ENDED, grant);
        return grant.getTargetUserId();
    }

    // ─── 本人・保護者側 ────────────────────────────────────

    /** 許可の前提（見える grant・PENDING・期限内・自分の側が未許可）の検証。対象本人の userId を返す。 */
    @Transactional(readOnly = true)
    public Long preflightApprove(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        requireApprovable(grant, isGuardianOnly(grant, userId), LocalDateTime.now(clock));
        return grant.getTargetUserId();
    }

    /** 許可を記録する（本人の状態・再認証は呼び出し側が TX の前に確認済み）。両者そろって初めて APPROVED。 */
    public SupportAccessTransitionResult approve(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        boolean asGuardian = isGuardianOnly(grant, userId);
        LocalDateTime now = LocalDateTime.now(clock);
        requireApprovable(grant, asGuardian, now);

        if (asGuardian) {
            grant.recordGuardianApproval(now, SupportAccessGrantService.GRANT_TTL_HOURS);
        } else {
            grant.recordTargetApproval(now, SupportAccessGrantService.GRANT_TTL_HOURS);
        }
        grantRepository.save(grant);
        if (grant.getStatus() == SupportAccessStatus.APPROVED) {
            publish(SupportAccessAuditAction.APPROVED, grant);
        } else {
            log.info("サポート閲覧: 片側の許可を記録（もう一方の許可待ち）grantId={}", grantId);
        }
        return result(grant);
    }

    /** 本人（または保護者）が拒否する（DENIED）。 */
    public SupportAccessTransitionResult deny(Long userId, UUID grantId, String comment) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (grant.getStatus() != SupportAccessStatus.PENDING || !now.isBefore(grant.getRequestExpiresAt())) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
        grant.deny(now, comment);
        grantRepository.save(grant);
        publish(SupportAccessAuditAction.DENIED, grant);
        return result(grant);
    }

    /** 本人（または保護者）はいつでも許可を取り消せる（REVOKED）。次のリクエストから即座に拒否される。 */
    public SupportAccessTransitionResult revoke(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        requireOpen(grant);
        grant.revoke(LocalDateTime.now(clock), SupportAccessEndedReason.USER_REVOKED);
        grantRepository.save(grant);
        publish(SupportAccessAuditAction.REVOKED, grant);
        return result(grant);
    }

    // ─── 退会・失効 ────────────────────────────────────────

    /**
     * 退会申請で本人の開いている grant を即座に REVOKED(WITHDRAWAL) にする。
     * 呼び出し元のトランザクションに関わらず確実に確定させるため REQUIRES_NEW。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SupportAccessGrantSnapshot> revokeAllForWithdrawal(Long targetUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SupportAccessGrantSnapshot> revoked = new ArrayList<>();
        for (SupportAccessGrantEntity grant : grantRepository.findByTargetUserIdAndStatusIn(targetUserId, OPEN_STATUSES)) {
            grant.revoke(now, SupportAccessEndedReason.WITHDRAWAL);
            grantRepository.save(grant);
            publish(SupportAccessAuditAction.REVOKED, grant);
            revoked.add(SupportAccessGrantSnapshot.from(grant));
        }
        return revoked;
    }

    /** PENDING(依頼72h)・APPROVED(許可24h) の期限切れを EXPIRED にし、失効させた grant を返す。 */
    public List<SupportAccessGrantSnapshot> expireOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SupportAccessGrantSnapshot> expired = new ArrayList<>();
        for (SupportAccessGrantEntity grant
                : grantRepository.findByStatusAndRequestExpiresAtBefore(SupportAccessStatus.PENDING, now)) {
            grant.expire();
            grantRepository.save(grant);
            expired.add(SupportAccessGrantSnapshot.from(grant));
        }
        for (SupportAccessGrantEntity grant
                : grantRepository.findByStatusAndExpiresAtBefore(SupportAccessStatus.APPROVED, now)) {
            grant.expire();
            grantRepository.save(grant);
            expired.add(SupportAccessGrantSnapshot.from(grant));
        }
        return expired;
    }

    // ─── 内部 ──────────────────────────────────────────────

    private static SupportAccessTransitionResult result(SupportAccessGrantEntity grant) {
        return new SupportAccessTransitionResult(SupportAccessGrantSnapshot.from(grant), List.of());
    }

    /** 開いている grant が期限切れか（PENDING は依頼期限、APPROVED は許可期限）。 */
    private static boolean isOverdue(SupportAccessGrantEntity grant, LocalDateTime now) {
        if (grant.getStatus() == SupportAccessStatus.PENDING) {
            return !now.isBefore(grant.getRequestExpiresAt());
        }
        return grant.getExpiresAt() != null && !now.isBefore(grant.getExpiresAt());
    }

    /** 承認済み・本人（と必要なら保護者）の許可が揃い・取消なし・期限内であること。 */
    private static void requireUsable(SupportAccessGrantEntity grant, LocalDateTime now) {
        boolean usable = grant.getStatus() == SupportAccessStatus.APPROVED
                && grant.getRevokedAt() == null
                && grant.getExpiresAt() != null
                && now.isBefore(grant.getExpiresAt())
                && grant.getDecidedAt() != null
                && (!grant.isGuardianApprovalRequired() || grant.getGuardianDecidedAt() != null);
        if (!usable) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }
    }

    private static void requireApprovable(SupportAccessGrantEntity grant, boolean asGuardian, LocalDateTime now) {
        if (grant.getStatus() != SupportAccessStatus.PENDING
                || !now.isBefore(grant.getRequestExpiresAt())
                || grant.hasApprovalBy(asGuardian)) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
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
