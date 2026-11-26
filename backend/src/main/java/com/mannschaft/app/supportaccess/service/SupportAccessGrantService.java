package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessTransitionResult;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * F10.9 依頼〜失効の状態遷移（運営側・本人側の両 API の入口）。
 *
 * <p><b>トランザクション境界</b>: 本クラスは {@code @Transactional} を持たない。他ドメイン（本人の状態・
 * 所属・保護者・再認証）への問い合わせは書込トランザクションの<b>前</b>に port 経由で済ませ、
 * 書込は {@link SupportAccessGrantTransitionService}（supportaccess の Repository だけに触れる）に任せ、
 * 監査（{@link SupportAccessAuditRecorder}）はコミット<b>後</b>に書く。書込トランザクションが他ドメインの
 * Repository へ到達しないようにするための分割（CLAUDE.md「{@code @Transactional} はドメイン内に閉じる」）。
 * 遷移通知は {@link com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent} を書込側が publish し、
 * AFTER_COMMIT で送る。</p>
 *
 * <p>Entity は返さない。遷移後の状態は読み取り専用の {@link SupportAccessGrantSnapshot} で返し、
 * Controller が {@code SupportAccessGrantQueryService} で表示名つきの応答 DTO に変換する。</p>
 */
@Slf4j
@Service
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

    private final SupportAccessGrantTransitionService transitions;
    private final SupportAccessUserDirectory userDirectory;
    private final SupportAccessOperatorAffiliationPort affiliationPort;
    private final SupportAccessReauthenticationVerifier reauthenticationVerifier;
    private final SupportAccessAuditRecorder auditRecorder;

    public SupportAccessGrantService(SupportAccessGrantTransitionService transitions,
                                     SupportAccessUserDirectory userDirectory,
                                     SupportAccessOperatorAffiliationPort affiliationPort,
                                     SupportAccessReauthenticationVerifier reauthenticationVerifier,
                                     SupportAccessAuditRecorder auditRecorder) {
        this.transitions = transitions;
        this.userDirectory = userDirectory;
        this.affiliationPort = affiliationPort;
        this.reauthenticationVerifier = reauthenticationVerifier;
        this.auditRecorder = auditRecorder;
    }

    // ─── 運営側 ────────────────────────────────────────────

    /** 運営者がサポート閲覧を依頼する（PENDING・72時間で失効）。 */
    public SupportAccessGrantSnapshot request(Long operatorUserId, CreateSupportAccessGrantRequest request) {
        String reason = normalizeReason(request.reason());
        Long targetUserId = request.targetUserId();

        if (affiliationPort.isAffiliatedWithAnyTenant(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATOR_AFFILIATED);
        }
        validateTarget(operatorUserId, targetUserId);
        Long guardianUserId = userDirectory.findRequiredGuardianUserId(targetUserId).orElse(null);

        SupportAccessTransitionResult result = transitions.request(operatorUserId, request, reason, guardianUserId);
        for (SupportAccessGrantSnapshot expired : result.expiredGrants()) {
            audit(SupportAccessAuditAction.EXPIRED, expired);
        }
        audit(SupportAccessAuditAction.REQUESTED, result.grant());
        return result.grant();
    }

    /** 運営者が依頼・許可を撤回する（CANCELLED）。 */
    public SupportAccessGrantSnapshot cancel(Long operatorUserId, UUID grantId) {
        SupportAccessGrantSnapshot grant = transitions.cancel(operatorUserId, grantId).grant();
        audit(SupportAccessAuditAction.CANCELLED, grant);
        return grant;
    }

    /** 閲覧開始。承認済みで期限内の grant だけが開始でき、以後のヘッダー付きリクエストが有効になる。 */
    public SupportAccessSessionResponse startSession(Long operatorUserId, UUID grantId) {
        Long targetUserId = transitions.preflightStartSession(operatorUserId, grantId);
        if (affiliationPort.isAffiliatedWithAnyTenant(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATOR_AFFILIATED);
        }
        SupportAccessUserSnapshot target = userDirectory.findUser(targetUserId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE));
        if (!STATUS_ACTIVE.equals(target.status()) || target.withdrawalPending() || target.systemAdmin()) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }

        SupportAccessSessionResponse response = transitions.startSession(operatorUserId, grantId, target.displayName());
        auditRecorder.record(SupportAccessAuditAction.SESSION_STARTED, operatorUserId, targetUserId, grantId);
        return response;
    }

    /** 閲覧終了。許可自体は期限まで残るが、ヘッダーは次のリクエストから拒否される。 */
    public void endSession(Long operatorUserId, UUID grantId) {
        Long targetUserId = transitions.endSession(operatorUserId, grantId);
        if (targetUserId != null) {
            auditRecorder.record(SupportAccessAuditAction.SESSION_ENDED, operatorUserId, targetUserId, grantId);
        }
    }

    // ─── 本人・保護者側 ────────────────────────────────────

    /** 本人または（未成年・後見下の場合）保護者の許可。両方そろって初めて APPROVED（期限は最後の許可+24h）。 */
    public SupportAccessGrantSnapshot approve(Long userId, UUID grantId, SupportAccessApproveRequest request) {
        Long targetUserId = transitions.preflightApprove(userId, grantId);
        requireTargetEligible(targetUserId);
        verifyReauthentication(userId, request);

        SupportAccessGrantSnapshot grant = transitions.approve(userId, grantId).grant();
        if (grant.status() == SupportAccessStatus.APPROVED) {
            audit(SupportAccessAuditAction.APPROVED, grant);
        }
        return grant;
    }

    /** 本人（または保護者）が拒否する（DENIED）。 */
    public SupportAccessGrantSnapshot deny(Long userId, UUID grantId, String comment) {
        SupportAccessGrantSnapshot grant = transitions.deny(userId, grantId, comment).grant();
        audit(SupportAccessAuditAction.DENIED, grant);
        return grant;
    }

    /** 本人（または保護者）はいつでも許可を取り消せる（REVOKED）。次のリクエストから即座に拒否される。 */
    public SupportAccessGrantSnapshot revoke(Long userId, UUID grantId) {
        SupportAccessGrantSnapshot grant = transitions.revoke(userId, grantId).grant();
        audit(SupportAccessAuditAction.REVOKED, grant);
        return grant;
    }

    // ─── 退会・失効 ────────────────────────────────────────

    /** 退会申請（WithdrawalRequestedEvent）で本人の開いている grant を即座に REVOKED(WITHDRAWAL) にする。 */
    public int revokeAllForWithdrawal(Long targetUserId) {
        List<SupportAccessGrantSnapshot> revoked = transitions.revokeAllForWithdrawal(targetUserId);
        revoked.forEach(grant -> audit(SupportAccessAuditAction.REVOKED, grant));
        return revoked.size();
    }

    /** 5分ごとのバッチ: PENDING(依頼72h)・APPROVED(許可24h) の期限切れを EXPIRED にする。 */
    public int expireOverdue() {
        List<SupportAccessGrantSnapshot> expired = transitions.expireOverdue();
        expired.forEach(grant -> audit(SupportAccessAuditAction.EXPIRED, grant));
        return expired.size();
    }

    // ─── 内部 ──────────────────────────────────────────────

    /** コミット後に監査を書く（他ドメインの監査 Service を書込トランザクションの外で呼ぶ）。 */
    private void audit(SupportAccessAuditAction action, SupportAccessGrantSnapshot grant) {
        auditRecorder.record(action, grant.operatorUserId(), grant.targetUserId(), grant.id());
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
}
