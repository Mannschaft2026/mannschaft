package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessTransitionResult;
import com.mannschaft.app.supportaccess.port.SupportAccessActingAsDetector;
import com.mannschaft.app.supportaccess.port.SupportAccessGuardianRequirement;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer.PreparedEmail;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * F10.9 依頼〜失効の状態遷移（運営側・本人側の両 API の入口）。
 *
 * <p><b>トランザクション境界</b>: 本クラスは {@code @Transactional} を持たない。他ドメイン（本人の状態・
 * 所属・保護者・再認証・後見切替の文脈）への問い合わせは書込トランザクションの<b>前</b>に port 経由で済ませ、
 * 書込は {@link SupportAccessGrantTransitionService}（supportaccess の Repository だけに触れる・grant 行を排他ロック）
 * に任せる。監査は遷移と同じトランザクションで監査 outbox に書かれ、コミット<b>後</b>に
 * {@link SupportAccessAuditRecorder} が audit_logs へ反映する（失敗は再試行バッチが拾う）。
 * 遷移通知は {@link com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent} を書込側が publish し、
 * AFTER_COMMIT で送る。</p>
 *
 * <p><b>ロック競合（第2回検分対応）</b>: 遷移の TX がロック待ちの上限（5秒）を超えた・デッドロックで選ばれた場合は
 * {@link #LOCK_CONFLICT_ATTEMPTS} 回まで（＝1回だけ再試行）やり直し、それでも取れなければ利用者には 409
 * （SUPPORT_ACCESS_007）で返す（500 にしない）。失効バッチ・退会の取消は1件ずつ別 TX で行い、取れなかった行は
 * 次の回（失効バッチ）・再試行（退会リスナー・照合）に回す。</p>
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
    /** ロック競合（待ちの上限超過・デッドロック）の試行回数（初回＋再試行1回）。 */
    static final int LOCK_CONFLICT_ATTEMPTS = 2;
    /** MySQL のロック待ちタイムアウト・デッドロックのエラー番号。 */
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    private final SupportAccessGrantTransitionService transitions;
    private final SupportAccessUserDirectory userDirectory;
    private final SupportAccessOperatorAffiliationPort affiliationPort;
    private final SupportAccessReauthenticationVerifier reauthenticationVerifier;
    private final SupportAccessActingAsDetector actingAsDetector;
    private final SupportAccessAuditRecorder auditRecorder;
    private final SupportAccessNoticeComposer noticeComposer;
    private final SupportAccessNotificationOutboxStore notificationOutboxStore;

    public SupportAccessGrantService(SupportAccessGrantTransitionService transitions,
                                     SupportAccessUserDirectory userDirectory,
                                     SupportAccessOperatorAffiliationPort affiliationPort,
                                     SupportAccessReauthenticationVerifier reauthenticationVerifier,
                                     SupportAccessActingAsDetector actingAsDetector,
                                     SupportAccessAuditRecorder auditRecorder,
                                     SupportAccessNoticeComposer noticeComposer,
                                     SupportAccessNotificationOutboxStore notificationOutboxStore) {
        this.transitions = transitions;
        this.userDirectory = userDirectory;
        this.affiliationPort = affiliationPort;
        this.reauthenticationVerifier = reauthenticationVerifier;
        this.actingAsDetector = actingAsDetector;
        this.auditRecorder = auditRecorder;
        this.noticeComposer = noticeComposer;
        this.notificationOutboxStore = notificationOutboxStore;
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
        Long guardianUserId = resolveGuardian(operatorUserId, targetUserId);

        // メールの宛先・文面（auth ドメインへの問い合わせ）は書込 TX の前に組み、TX の中で冪等キーを付けて enqueue する
        List<PreparedEmail> emails = new ArrayList<>();
        noticeComposer.prepareEmail(targetUserId, SupportAccessNotificationKind.REQUESTED_TO_TARGET,
                operatorUserId, targetUserId, reason).ifPresent(emails::add);
        if (guardianUserId != null) {
            noticeComposer.prepareEmail(guardianUserId, SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN,
                    operatorUserId, targetUserId, reason).ifPresent(emails::add);
        }

        SupportAccessTransitionResult result = retryOnLockConflict(
                () -> transitions.request(operatorUserId, request, reason, guardianUserId, emails));
        for (SupportAccessGrantSnapshot expired : result.expiredGrants()) {
            auditRecorder.relayForGrant(expired.id());
        }
        auditRecorder.relayForGrant(result.grant().id());
        return result.grant();
    }

    /** 運営者が依頼・許可を撤回する（CANCELLED）。 */
    public SupportAccessGrantSnapshot cancel(Long operatorUserId, UUID grantId) {
        SupportAccessGrantSnapshot grant = retryOnLockConflict(() -> transitions.cancel(operatorUserId, grantId)).grant();
        auditRecorder.relayForGrant(grantId);
        return grant;
    }

    /** 閲覧開始。承認済みで期限内の grant だけが開始でき、以後のヘッダー付きリクエストが有効になる。 */
    public SupportAccessSessionResponse startSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantSnapshot preflight = transitions.preflightStartSession(operatorUserId, grantId);
        Long targetUserId = preflight.targetUserId();
        if (affiliationPort.isAffiliatedWithAnyTenant(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATOR_AFFILIATED);
        }
        SupportAccessUserSnapshot target = userDirectory.findUser(targetUserId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE));
        if (!STATUS_ACTIVE.equals(target.status()) || target.withdrawalPending() || target.systemAdmin()) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }

        List<PreparedEmail> emails = noticeComposer.prepareEmail(targetUserId,
                SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET, operatorUserId, targetUserId,
                preflight.requestReason()).map(List::of).orElse(List.of());
        SupportAccessSessionResponse response = retryOnLockConflict(
                () -> transitions.startSession(operatorUserId, grantId, target.displayName(), emails));
        auditRecorder.relayForGrant(grantId);
        return response;
    }

    /** 閲覧終了。許可自体は期限まで残るが、ヘッダーは次のリクエストから拒否される。 */
    public void endSession(Long operatorUserId, UUID grantId) {
        if (retryOnLockConflict(() -> transitions.endSession(operatorUserId, grantId)) != null) {
            auditRecorder.relayForGrant(grantId);
        }
    }

    // ─── 本人・保護者側 ────────────────────────────────────

    /** 本人または（未成年・後見下の場合）保護者の許可。両方そろって初めて APPROVED（期限は最後の許可+24h）。 */
    public SupportAccessGrantSnapshot approve(Long userId, UUID grantId, SupportAccessApproveRequest request) {
        requireNotActingAs();
        SupportAccessGrantSnapshot preflight = transitions.preflightApprove(userId, grantId);
        requireTargetEligible(preflight.targetUserId());
        requireGuardianStillValid(preflight);
        verifyReauthentication(userId, request);

        SupportAccessGrantSnapshot grant = retryOnLockConflict(() -> transitions.approve(userId, grantId)).grant();
        auditRecorder.relayForGrant(grantId);
        return grant;
    }

    /** 本人（または保護者）が拒否する（DENIED）。 */
    public SupportAccessGrantSnapshot deny(Long userId, UUID grantId, String comment) {
        requireNotActingAs();
        SupportAccessGrantSnapshot grant = retryOnLockConflict(() -> transitions.deny(userId, grantId, comment)).grant();
        auditRecorder.relayForGrant(grantId);
        return grant;
    }

    /** 本人（または保護者）はいつでも許可を取り消せる（REVOKED）。次のリクエストから即座に拒否される。 */
    public SupportAccessGrantSnapshot revoke(Long userId, UUID grantId) {
        requireNotActingAs();
        SupportAccessGrantSnapshot grant = retryOnLockConflict(() -> transitions.revoke(userId, grantId)).grant();
        auditRecorder.relayForGrant(grantId);
        return grant;
    }

    // ─── 退会・失効・監査の再試行 ──────────────────────────

    /**
     * 退会申請（WithdrawalRequestedEvent）で本人の開いている grant を即座に REVOKED(WITHDRAWAL) にする。
     * id をロックなしで集め、1件ずつ別 TX で主キーをロックして取り消す（ロック順の統一）。ロックが取れない行が
     * 残れば例外にする（退会リスナーが再試行し、それでも残れば失効バッチの照合が取り消す）。
     */
    public int revokeAllForWithdrawal(Long targetUserId) {
        int revoked = 0;
        RuntimeException conflict = null;
        for (UUID grantId : transitions.findOpenGrantIdsForTarget(targetUserId)) {
            try {
                Optional<SupportAccessGrantSnapshot> grant =
                        retryLockConflictOnce(() -> transitions.revokeForWithdrawal(grantId, targetUserId));
                if (grant.isPresent()) {
                    auditRecorder.relayForGrant(grantId);
                    revoked++;
                }
            } catch (RuntimeException e) {
                if (!isLockConflict(e)) {
                    throw e;
                }
                // 他の行は続けて取り消す。取れなかった行は最後に例外で知らせ、再試行・照合に回す
                log.warn("サポート閲覧: 退会の取消でロックが取れない（再試行に回す）grantId={}, error={}", grantId, e.toString());
                conflict = e;
            }
        }
        if (conflict != null) {
            throw conflict;
        }
        return revoked;
    }

    /**
     * 退会リスナーの取りこぼしの照合（失効バッチから呼ぶ）。開いている grant の本人のうち、退会手続中・見つからない
     * 本人の grant を REVOKED(WITHDRAWAL) にする。リスナーが失敗・停止しても、次のバッチで必ず取り消される。
     */
    public int reconcileWithdrawnTargets() {
        int revoked = 0;
        for (Long targetUserId : transitions.findOpenTargetUserIds()) {
            Optional<SupportAccessUserSnapshot> target = userDirectory.findUser(targetUserId);
            if (target.isEmpty() || target.get().withdrawalPending()) {
                revoked += revokeAllForWithdrawal(targetUserId);
            }
        }
        return revoked;
    }

    /**
     * 5分ごとのバッチ: PENDING(依頼72h)・APPROVED(許可24h) の期限切れを EXPIRED にする。id をロックなしで集め、
     * 1件ずつ別 TX で主キーをロックして期限を再検証する。ロックが取れない行（本人が操作中など）は次の回に回す
     * （期限の判定自体は Validator が毎リクエスト時刻で行うため、失効の確定が5分遅れても延命されない）。
     */
    public int expireOverdue() {
        int expired = 0;
        for (UUID grantId : transitions.findOverdueIds()) {
            try {
                if (retryLockConflictOnce(() -> transitions.expireIfOverdue(grantId)).isPresent()) {
                    auditRecorder.relayForGrant(grantId);
                    expired++;
                }
            } catch (RuntimeException e) {
                if (!isLockConflict(e)) {
                    throw e;
                }
                log.warn("サポート閲覧: 失効バッチでロックが取れない（次の回に回す）grantId={}, error={}", grantId, e.toString());
            }
        }
        return expired;
    }

    /** 再試行バッチ: 反映できていない監査 outbox を audit_logs へ反映し、未配送の通知 outbox の再配送を起こす。 */
    public int relayPendingAudits() {
        long pendingNotifications = notificationOutboxStore.requestRelayOfDue();
        if (pendingNotifications > 0) {
            log.info("サポート閲覧: 未配送の通知の再配送を依頼 件数={}", pendingNotifications);
        }
        return auditRecorder.relayDue();
    }

    // ─── 内部 ──────────────────────────────────────────────

    /**
     * 利用者の操作の遷移: ロック競合なら1回だけやり直し、それでも取れなければ 409（SUPPORT_ACCESS_007）。
     * 業務の拒否（{@link BusinessException}）はそのまま返す。
     */
    private <T> T retryOnLockConflict(Supplier<T> transition) {
        try {
            return retryLockConflictOnce(transition);
        } catch (RuntimeException e) {
            if (isLockConflict(e)) {
                log.warn("サポート閲覧: 遷移のロック競合が再試行でも解けない（409 で返す）error={}", e.toString());
                throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION, e);
            }
            throw e;
        }
    }

    /** ロック競合なら1回だけやり直す（2回目の失敗はそのまま投げる）。 */
    static <T> T retryLockConflictOnce(Supplier<T> transition) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= LOCK_CONFLICT_ATTEMPTS; attempt++) {
            try {
                return transition.get();
            } catch (RuntimeException e) {
                if (!isLockConflict(e)) {
                    throw e;
                }
                last = e;
                log.info("サポート閲覧: 遷移のロック競合（{}/{}）error={}", attempt, LOCK_CONFLICT_ATTEMPTS, e.toString());
            }
        }
        throw last;
    }

    /** ロック待ちの上限超過・デッドロックか（Spring の変換後・JPA・Hibernate・JDBC のどの形でも、原因の連鎖で判定）。 */
    public static boolean isLockConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PessimisticLockingFailureException || t instanceof PessimisticLockException
                    || t instanceof LockTimeoutException || t instanceof LockAcquisitionException) {
                return true;
            }
            if (t instanceof SQLException sql
                    && (sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT || sql.getErrorCode() == MYSQL_DEADLOCK
                    || "40001".equals(sql.getSQLState()))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
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

    /**
     * 依頼時の保護者（fail-closed）。保護者が要るのに承認できる保護者がいなければ 005。
     * 承認できる保護者に SYSTEM_ADMIN（運営アカウント）や依頼した運営者本人がいれば、利益相反として 005。
     * 既存の保護者同意と同じく、いずれか1人（userId 昇順の先頭）の承認で足りる。
     */
    private Long resolveGuardian(Long operatorUserId, Long targetUserId) {
        SupportAccessGuardianRequirement requirement = userDirectory.resolveGuardianRequirement(targetUserId);
        if (!requirement.required()) {
            return null;
        }
        if (requirement.unresolvable()) {
            throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
        }
        for (Long guardian : requirement.eligibleGuardianUserIds()) {
            if (isConflictingGuardian(operatorUserId, guardian)) {
                throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
            }
        }
        return requirement.eligibleGuardianUserIds().get(0);
    }

    /**
     * 許可の時点で保護者の要否を再評価する。依頼の後に保護者が停止・リンク解除・後見の権原切れになった、
     * または依頼の時は不要だったのに要るようになった場合は、依頼をやり直させる（005）。
     */
    private void requireGuardianStillValid(SupportAccessGrantSnapshot grant) {
        SupportAccessGuardianRequirement requirement = userDirectory.resolveGuardianRequirement(grant.targetUserId());
        if (!grant.guardianApprovalRequired()) {
            if (requirement.required()) {
                throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
            }
            return;
        }
        Long guardian = grant.guardianUserId();
        if (guardian == null || !requirement.eligibleGuardianUserIds().contains(guardian)
                || isConflictingGuardian(grant.operatorUserId(), guardian)) {
            throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
        }
    }

    private boolean isConflictingGuardian(Long operatorUserId, Long guardianUserId) {
        if (guardianUserId == null || guardianUserId.equals(operatorUserId)) {
            return true;
        }
        return userDirectory.findUser(guardianUserId).map(SupportAccessUserSnapshot::systemAdmin).orElse(true);
    }

    /** 許可の時点で本人が ACTIVE であること（保護者同意待ち・凍結・退会手続中の間は許可できない）。 */
    private void requireTargetEligible(Long targetUserId) {
        SupportAccessUserSnapshot target = userDirectory.findUser(targetUserId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.TARGET_INVALID));
        if (!STATUS_ACTIVE.equals(target.status()) || target.withdrawalPending()) {
            throw new BusinessException(SupportAccessErrorCode.TARGET_INVALID);
        }
    }

    /** 後見切替・代理入力の最中は、本人（保護者）の意思表示である許可・拒否・取消を行わせない（015）。 */
    private void requireNotActingAs() {
        if (actingAsDetector.isActingAsAnotherUser()) {
            throw new BusinessException(SupportAccessErrorCode.ACTING_AS_FORBIDDEN);
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
