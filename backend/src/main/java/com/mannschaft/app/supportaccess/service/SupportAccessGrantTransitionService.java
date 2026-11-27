package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.supportaccess.SupportAccessNotificationKind;
import com.mannschaft.app.supportaccess.SupportAccessAuditAction;
import com.mannschaft.app.supportaccess.SupportAccessEndedReason;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessEventKind;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessTransitionResult;
import com.mannschaft.app.supportaccess.entity.SupportAccessAuditOutboxEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessNotificationOutboxEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository;
import com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer.PreparedEmail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * F10.9 状態遷移の書込トランザクション（supportaccess ドメインの Repository だけに触れる）。
 *
 * <p>他ドメイン（本人の状態・所属・保護者・再認証）への問い合わせと audit_logs への反映は、このクラスの外
 * （{@link SupportAccessGrantService}・トランザクション開始前／コミット後）で行う。CLAUDE.md の
 * 「{@code @Transactional} はドメイン内に閉じる」に従い、port 実装経由で他ドメインの Repository に
 * 到達するトランザクションを作らないための分割。値は {@link SupportAccessGrantSnapshot} で返し、
 * Entity は公開しない。</p>
 *
 * <p><b>排他（検分差し戻し対応）</b>: 遷移する全メソッドは grant 行を {@code SELECT ... FOR UPDATE}
 * （{@link SupportAccessGrantRepository#findByIdForUpdate} 等）で読み、ロックを持ったまま状態を検証して書く。
 * ロックなしで読むと、同時の「閲覧開始」と「取消」、「許可」と「撤回」、「本人の許可」と「保護者の許可」の
 * どちらかが後勝ちで上書きされて消える。ロックの取得順は「運営者の依頼ロック → grant 行の<b>主キー</b>（id 昇順）
 * → 新しい行の INSERT」に統一し、二次索引を走査してロックする読み取りは使わない（第2回検分: 失効バッチ・退会が
 * 二次索引 → 主キー、本人の操作が主キー → 二次索引の逆順でデッドロックしていた）。複数行の失効・退会は id を
 * ロックなしで集め、1件ずつ別 TX で主キーをロックして再検証する。ロック待ちは5秒で打ち切る（{@link #withShortLockWait}）。
 * TX の外で済ませた事前判定（preflight）は信用せず、ロック後に状態の検証をやり直す。</p>
 *
 * <p><b>安全通知の欠落防止（第2回検分対応）</b>: 依頼・閲覧開始・許可・拒否・取消の通知は、遷移と同じトランザクションで
 * アプリ内通知の outbox（support_access_notification_outbox）に宛先ごとに1行、本人・保護者へのメールは
 * メール outbox（F09.18 {@link EmailOutboxService}）に1行書く。遷移が巻き戻れば通知・メールも残らず、確定すれば必ず残る。
 * メール outbox への書込は mail ドメインの表への越境だが、F09.18 の設計（呼び出し側の TX の中で enqueue する
 * transactional outbox）どおりの入口であり、送信自体はメールの Worker がコミット後に行う。冪等キーは
 * grantId＋種別＋宛先（閲覧開始は開始時刻）で決まる値で、同じ遷移から2通は出ない。</p>
 *
 * <p><b>監査の欠落防止</b>: 遷移ごとに、同じトランザクションで監査 outbox（support_access_audit_outbox）へ1行書く。
 * 遷移が確定すれば監査の元も必ず確定し、audit_logs への反映はコミット後に {@link SupportAccessAuditRecorder} が
 * 行う（失敗は outbox に残り再試行バッチが拾う）。閲覧開始・終了の閲覧記録（SESSION_START/END）も同じ
 * トランザクションで書く（grant 行の排他ロックを持ったまま REQUIRES_NEW で子行を書くと、FK の親行ロック待ちで
 * 自分自身を待ってしまうため）。</p>
 */
@Slf4j
@Service
@Transactional
public class SupportAccessGrantTransitionService {

    private static final Set<SupportAccessStatus> OPEN_STATUSES =
            EnumSet.of(SupportAccessStatus.PENDING, SupportAccessStatus.APPROVED);

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessLogRepository logRepository;
    private final SupportAccessAuditOutboxRepository auditOutboxRepository;
    private final SupportAccessNotificationOutboxRepository notificationOutboxRepository;
    private final EmailOutboxService emailOutboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SupportAccessGrantTransitionService(SupportAccessGrantRepository grantRepository,
                                               SupportAccessLogRepository logRepository,
                                               SupportAccessAuditOutboxRepository auditOutboxRepository,
                                               SupportAccessNotificationOutboxRepository notificationOutboxRepository,
                                               EmailOutboxService emailOutboxService,
                                               ApplicationEventPublisher eventPublisher,
                                               Clock clock) {
        this.grantRepository = grantRepository;
        this.logRepository = logRepository;
        this.auditOutboxRepository = auditOutboxRepository;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.emailOutboxService = emailOutboxService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ─── 運営側 ────────────────────────────────────────────

    /**
     * 依頼を作る。guardianUserId は呼び出し側が TX の外で解決した値（null なら保護者の承認は不要）。
     * emails は呼び出し側が TX の前に組んだ本人・保護者へのメール（ここで冪等キーを付けて同じ TX で enqueue する）。
     */
    public SupportAccessTransitionResult request(Long operatorUserId, CreateSupportAccessGrantRequest request,
                                                 String reason, Long guardianUserId, List<PreparedEmail> emails) {
        return withShortLockWait(() -> doRequest(operatorUserId, request, reason, guardianUserId, emails));
    }

    private SupportAccessTransitionResult doRequest(Long operatorUserId, CreateSupportAccessGrantRequest request,
                                                    String reason, Long guardianUserId, List<PreparedEmail> emails) {
        // 同じ運営者の依頼を直列化する（1日20件の count→insert と、同じ組合せの重複の判定を競合させない）
        grantRepository.lockOperatorForRequest(operatorUserId);

        Instant now = Instant.now(clock);
        Long targetUserId = request.targetUserId();
        List<SupportAccessGrantSnapshot> expired = new ArrayList<>();

        // 開いている grant が期限切れ（バッチ前）なら、その場で失効してから新しい依頼を作る。
        // id はロックなしで集め、主キーで1件ずつロックしてから状態を再検証する（二次索引を走査してロックしない）
        for (UUID openId : grantRepository.findOpenIds(operatorUserId, targetUserId, OPEN_STATUSES)) {
            SupportAccessGrantEntity open = grantRepository.findByIdForUpdate(openId).orElse(null);
            if (open == null || !open.isOpen() || !open.getOperatorUserId().equals(operatorUserId)
                    || !open.getTargetUserId().equals(targetUserId)) {
                // 読んでからロックするまでに失効・取消などで閉じた（または消えた）
                continue;
            }
            if (!isOverdue(open, now)) {
                throw new BusinessException(SupportAccessErrorCode.DUPLICATE_OPEN_GRANT);
            }
            open.expire();
            appendAudit(SupportAccessAuditAction.EXPIRED, open, now);
            // 同じ組合せの一意制約（open_flag）に当たらないよう、失効を先に確定する
            grantRepository.saveAndFlush(open);
            expired.add(SupportAccessGrantSnapshot.from(open));
        }

        if (grantRepository.countByOperatorUserIdAndRequestedAtAfter(operatorUserId, now.minus(Duration.ofDays(1)))
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
                .requestExpiresAt(now.plus(Duration.ofHours(SupportAccessGrantService.REQUEST_TTL_HOURS)))
                .guardianApprovalRequired(guardianUserId != null)
                .guardianUserId(guardianUserId)
                .build();
        SupportAccessGrantEntity saved;
        try {
            saved = grantRepository.save(grant);
            grantRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 同時依頼の競合（open_flag の一意制約）。依頼ロックで通常は起きないが、最後の砦として 006 にする
            throw new BusinessException(SupportAccessErrorCode.DUPLICATE_OPEN_GRANT, e);
        }
        record(SupportAccessAuditAction.REQUESTED, saved, now);
        enqueueEmails(emails, saved.getId(), null);
        return new SupportAccessTransitionResult(SupportAccessGrantSnapshot.from(saved), expired);
    }

    /** 運営者が依頼・許可を撤回する（CANCELLED）。 */
    public SupportAccessTransitionResult cancel(Long operatorUserId, UUID grantId) {
        return withShortLockWait(() -> {
            SupportAccessGrantEntity grant = lockOwnedByOperator(operatorUserId, grantId);
            requireOpen(grant);
            Instant now = Instant.now(clock);
            grant.cancel(now);
            grantRepository.save(grant);
            record(SupportAccessAuditAction.CANCELLED, grant, now);
            return result(grant);
        });
    }

    /** 閲覧開始の前提（自分の grant・承認済みで期限内）の検証。grant の写しを返す（ロックしない事前判定）。 */
    @Transactional(readOnly = true)
    public SupportAccessGrantSnapshot preflightStartSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = findOwnedByOperator(operatorUserId, grantId);
        requireUsable(grant, Instant.now(clock));
        return SupportAccessGrantSnapshot.from(grant);
    }

    /**
     * 閲覧開始。承認済みで期限内の grant だけが開始できる（所属・本人の状態は呼び出し側が TX の前に確認済み）。
     * 本人への通知（アプリ内・メール）は同じ TX で outbox に書く（閲覧が始まったのに本人へ知らせが残らない、を作らない）。
     */
    public SupportAccessSessionResponse startSession(Long operatorUserId, UUID grantId, String targetDisplayName,
                                                     List<PreparedEmail> emails) {
        return withShortLockWait(() -> {
            SupportAccessGrantEntity grant = lockOwnedByOperator(operatorUserId, grantId);
            Instant now = Instant.now(clock);
            // ロック後に検証し直す（事前判定の後に取消・撤回・失効が確定していれば、ここで 002 になる）
            requireUsable(grant, now);

            grant.startSession(now);
            grantRepository.save(grant);
            appendSessionLog(grant, SupportAccessEventKind.SESSION_START, now);
            record(SupportAccessAuditAction.SESSION_STARTED, grant, now);
            enqueueEmails(emails, grant.getId(), now);
            return new SupportAccessSessionResponse(grantId, grant.getTargetUserId(), targetDisplayName,
                    grant.getExpiresAt(), remainingSeconds(grant.getExpiresAt(), now));
        });
    }

    /** 閲覧終了。終了済みなら何もせず null（冪等）、終了したら対象本人の userId を返す。 */
    public Long endSession(Long operatorUserId, UUID grantId) {
        return withShortLockWait(() -> doEndSession(operatorUserId, grantId));
    }

    private Long doEndSession(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = lockOwnedByOperator(operatorUserId, grantId);
        if (grant.getActiveSessionStartedAt() == null) {
            throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
        }
        if (!grant.isSessionActive()) {
            return null;
        }
        Instant now = Instant.now(clock);
        grant.endSession(now);
        grantRepository.save(grant);
        appendSessionLog(grant, SupportAccessEventKind.SESSION_END, now);
        record(SupportAccessAuditAction.SESSION_ENDED, grant, now);
        return grant.getTargetUserId();
    }

    // ─── 本人・保護者側 ────────────────────────────────────

    /** 許可の前提（見える grant・PENDING・期限内・自分の側が未許可）の検証。grant の写しを返す（ロックしない事前判定）。 */
    @Transactional(readOnly = true)
    public SupportAccessGrantSnapshot preflightApprove(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = findVisibleToUser(userId, grantId);
        requireApprovable(grant, isGuardianOnly(grant, userId), Instant.now(clock));
        return SupportAccessGrantSnapshot.from(grant);
    }

    /**
     * 許可を記録する（本人の状態・再認証は呼び出し側が TX の前に確認済み）。両者そろって初めて APPROVED。
     * 本人と保護者が同時に許可しても、grant 行の排他ロックで1件ずつ処理されるため、片方の許可が消えない。
     */
    public SupportAccessTransitionResult approve(Long userId, UUID grantId) {
        return withShortLockWait(() -> doApprove(userId, grantId));
    }

    private SupportAccessTransitionResult doApprove(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = lockVisibleToUser(userId, grantId);
        boolean asGuardian = isGuardianOnly(grant, userId);
        Instant now = Instant.now(clock);
        requireApprovable(grant, asGuardian, now);

        if (asGuardian) {
            grant.recordGuardianApproval(now, SupportAccessGrantService.GRANT_TTL_HOURS);
        } else {
            grant.recordTargetApproval(now, SupportAccessGrantService.GRANT_TTL_HOURS);
        }
        grantRepository.save(grant);
        if (grant.getStatus() == SupportAccessStatus.APPROVED) {
            record(SupportAccessAuditAction.APPROVED, grant, now);
        } else {
            log.info("サポート閲覧: 片側の許可を記録（もう一方の許可待ち）grantId={}", grantId);
        }
        return result(grant);
    }

    /** 本人（または保護者）が拒否する（DENIED）。 */
    public SupportAccessTransitionResult deny(Long userId, UUID grantId, String comment) {
        return withShortLockWait(() -> {
            SupportAccessGrantEntity grant = lockVisibleToUser(userId, grantId);
            Instant now = Instant.now(clock);
            if (grant.getStatus() != SupportAccessStatus.PENDING || !now.isBefore(grant.getRequestExpiresAt())) {
                throw new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION);
            }
            grant.deny(now, comment);
            grantRepository.save(grant);
            record(SupportAccessAuditAction.DENIED, grant, now);
            return result(grant);
        });
    }

    /** 本人（または保護者）はいつでも許可を取り消せる（REVOKED）。次のリクエストから即座に拒否される。 */
    public SupportAccessTransitionResult revoke(Long userId, UUID grantId) {
        return withShortLockWait(() -> {
            SupportAccessGrantEntity grant = lockVisibleToUser(userId, grantId);
            requireOpen(grant);
            Instant now = Instant.now(clock);
            grant.revoke(now, SupportAccessEndedReason.USER_REVOKED);
            grantRepository.save(grant);
            record(SupportAccessAuditAction.REVOKED, grant, now);
            return result(grant);
        });
    }

    // ─── 退会・失効 ────────────────────────────────────────

    /** 退会用: 本人の開いている grant の id（ロックしない。{@link #revokeForWithdrawal} で1件ずつ取り消す）。 */
    @Transactional(readOnly = true)
    public List<UUID> findOpenGrantIdsForTarget(Long targetUserId) {
        return grantRepository.findOpenIdsByTargetUserId(targetUserId, OPEN_STATUSES);
    }

    /**
     * 退会申請で本人の開いている grant を1件 REVOKED(WITHDRAWAL) にする（主キーでロックしてから再検証）。
     * 呼び出し元のトランザクションに関わらず確実に確定させるため REQUIRES_NEW。REVOKED は終端で、
     * 退会を撤回しても PENDING・APPROVED へは戻らない（戻す経路を持たない）。既に閉じていれば empty。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SupportAccessGrantSnapshot> revokeForWithdrawal(UUID grantId, Long targetUserId) {
        return withShortLockWait(() -> {
            SupportAccessGrantEntity grant = grantRepository.findByIdForUpdate(grantId).orElse(null);
            if (grant == null || !grant.isOpen() || !grant.getTargetUserId().equals(targetUserId)) {
                return Optional.empty();
            }
            Instant now = Instant.now(clock);
            grant.revoke(now, SupportAccessEndedReason.WITHDRAWAL);
            grantRepository.save(grant);
            record(SupportAccessAuditAction.REVOKED, grant, now);
            return Optional.of(SupportAccessGrantSnapshot.from(grant));
        });
    }

    /** 開いている grant の本人（退会の取りこぼしの照合用）。 */
    @Transactional(readOnly = true)
    public Set<Long> findOpenTargetUserIds() {
        Set<Long> targets = new LinkedHashSet<>();
        for (SupportAccessGrantEntity grant : grantRepository.findByStatusIn(OPEN_STATUSES)) {
            targets.add(grant.getTargetUserId());
        }
        return targets;
    }

    /** 失効バッチ用: 期限に達した grant の id（ロックしない。{@link #expireIfOverdue} で1件ずつ失効させる）。 */
    @Transactional(readOnly = true)
    public List<UUID> findOverdueIds() {
        return grantRepository.findOverdueIds(Instant.now(clock));
    }

    /**
     * 期限に達した grant を1件 EXPIRED にする（主キーでロックしてから期限を再検証）。読んでからロックするまでに
     * 本人の取消・許可などで状態が変わっていれば何もしない（empty）。境界は Validator（{@code now < expiresAt} の
     * ときだけ有効）に揃え、期限ちょうども失効とする。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SupportAccessGrantSnapshot> expireIfOverdue(UUID grantId) {
        return withShortLockWait(() -> {
            SupportAccessGrantEntity grant = grantRepository.findByIdForUpdate(grantId).orElse(null);
            Instant now = Instant.now(clock);
            if (grant == null || !grant.isOpen() || !isOverdue(grant, now)) {
                return Optional.empty();
            }
            grant.expire();
            grantRepository.save(grant);
            appendAudit(SupportAccessAuditAction.EXPIRED, grant, now);
            return Optional.of(SupportAccessGrantSnapshot.from(grant));
        });
    }

    // ─── 内部 ──────────────────────────────────────────────

    private static SupportAccessTransitionResult result(SupportAccessGrantEntity grant) {
        return new SupportAccessTransitionResult(SupportAccessGrantSnapshot.from(grant), List.of());
    }

    /** 残り秒数（BE の時刻基準。FE はこれを基準に残り時間を表示する）。 */
    static long remainingSeconds(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(now, expiresAt).getSeconds());
    }

    /** 開いている grant が期限切れか（PENDING は依頼期限、APPROVED は許可期限）。 */
    private static boolean isOverdue(SupportAccessGrantEntity grant, Instant now) {
        if (grant.getStatus() == SupportAccessStatus.PENDING) {
            return !now.isBefore(grant.getRequestExpiresAt());
        }
        return grant.getExpiresAt() != null && !now.isBefore(grant.getExpiresAt());
    }

    /** 承認済み・本人（と必要なら保護者）の許可が揃い・取消なし・期限内であること。 */
    private static void requireUsable(SupportAccessGrantEntity grant, Instant now) {
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

    private static void requireApprovable(SupportAccessGrantEntity grant, boolean asGuardian, Instant now) {
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

    /**
     * ロック待ちの上限を5秒にして本体を実行し、必ず既定に戻す（接続はプールで再利用される）。
     * 5秒を超える待ち・デッドロックは例外のまま呼び出し側へ返し、{@link SupportAccessGrantService} が1回だけ再試行する。
     */
    private <T> T withShortLockWait(Supplier<T> body) {
        grantRepository.shortenLockWait();
        try {
            return body.get();
        } finally {
            grantRepository.restoreLockWait();
        }
    }

    /** 遷移の監査 outbox と通知 outbox を書き、配送を起こすイベントを出す（どれもこのトランザクションに乗る）。 */
    private void record(SupportAccessAuditAction action, SupportAccessGrantEntity grant, Instant now) {
        appendAudit(action, grant, now);
        appendNotifications(action, grant, now);
        eventPublisher.publishEvent(new SupportAccessGrantChangedEvent(action, grant.getId(),
                grant.getOperatorUserId(), grant.getTargetUserId(), grant.getGuardianUserId()));
    }

    /** 遷移ごとの通知の宛先（本人へ: 依頼・閲覧開始。保護者へ: 依頼。運営者へ: 許可・拒否・取消）。 */
    private void appendNotifications(SupportAccessAuditAction action, SupportAccessGrantEntity grant, Instant now) {
        switch (action) {
            case REQUESTED -> {
                appendNotification(SupportAccessNotificationKind.REQUESTED_TO_TARGET, grant.getTargetUserId(),
                        grant, now, null);
                if (grant.isGuardianApprovalRequired() && grant.getGuardianUserId() != null) {
                    appendNotification(SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN, grant.getGuardianUserId(),
                            grant, now, null);
                }
            }
            case SESSION_STARTED -> appendNotification(SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET,
                    grant.getTargetUserId(), grant, now, now);
            case APPROVED -> appendNotification(SupportAccessNotificationKind.APPROVED_TO_OPERATOR,
                    grant.getOperatorUserId(), grant, now, null);
            case DENIED -> appendNotification(SupportAccessNotificationKind.DENIED_TO_OPERATOR,
                    grant.getOperatorUserId(), grant, now, null);
            case REVOKED -> appendNotification(SupportAccessNotificationKind.REVOKED_TO_OPERATOR,
                    grant.getOperatorUserId(), grant, now, null);
            default -> {
                // CANCELLED・EXPIRED・SESSION_ENDED・DENIED_REQUEST は通知しない（監査にのみ残る）
            }
        }
    }

    private void appendNotification(SupportAccessNotificationKind kind, Long recipientUserId,
                                    SupportAccessGrantEntity grant, Instant now, Instant sessionStartedAt) {
        String key = SupportAccessNoticeComposer.noticeKey(kind, grant.getId(), recipientUserId, sessionStartedAt);
        if (notificationOutboxRepository.existsByIdempotencyKey(key)) {
            // 同じ遷移の通知は1回だけ（UNIQUE 違反で遷移の TX を巻き戻さないよう、先に確かめる）
            return;
        }
        notificationOutboxRepository.save(SupportAccessNotificationOutboxEntity.builder()
                .idempotencyKey(key)
                .grantId(grant.getId())
                .notificationKind(kind.name())
                .recipientUserId(recipientUserId)
                .targetUserId(grant.getTargetUserId())
                .occurredAt(now)
                .attemptCount(0)
                .nextAttemptAt(now)
                .build());
    }

    /**
     * 本人・保護者へのメールを、遷移と同じ TX でメール outbox に enqueue する（冪等キーは決定的な値）。
     * enqueue に失敗したら遷移ごと巻き戻す（本人へ知らせを残せない閲覧開始・依頼を確定させない: fail-closed）。
     */
    private void enqueueEmails(List<PreparedEmail> emails, UUID grantId, Instant sessionStartedAt) {
        if (emails == null) {
            return;
        }
        for (PreparedEmail email : emails) {
            emailOutboxService.enqueue(email.toRequest(grantId, sessionStartedAt));
        }
    }

    /** 監査 outbox へ1行（遷移と同じトランザクション）。audit_logs への反映はコミット後。 */
    private void appendAudit(SupportAccessAuditAction action, SupportAccessGrantEntity grant, Instant now) {
        auditOutboxRepository.save(SupportAccessAuditOutboxEntity.builder()
                .grantId(grant.getId())
                .eventType(SupportAccessAuditRecorder.EVENT_PREFIX + action.name())
                .operatorUserId(grant.getOperatorUserId())
                .targetUserId(grant.getTargetUserId())
                .occurredAt(now)
                .attemptCount(0)
                .nextAttemptAt(now)
                .build());
    }

    /** 閲覧開始・終了の閲覧記録（grant 行のロックを持ったまま、同じトランザクションで書く）。 */
    private void appendSessionLog(SupportAccessGrantEntity grant, SupportAccessEventKind kind, Instant now) {
        logRepository.save(SupportAccessLogEntity.builder()
                .grantId(grant.getId())
                .operatorUserId(grant.getOperatorUserId())
                .targetUserId(grant.getTargetUserId())
                .eventKind(kind)
                .occurredAt(now)
                .build());
    }

    /** 事前判定用（ロックしない読み取り）。 */
    private SupportAccessGrantEntity findOwnedByOperator(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return requireOwnedByOperator(grant, operatorUserId);
    }

    /** 遷移用（排他ロックして読む）。 */
    private SupportAccessGrantEntity lockOwnedByOperator(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findByIdForUpdate(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return requireOwnedByOperator(grant, operatorUserId);
    }

    private static SupportAccessGrantEntity requireOwnedByOperator(SupportAccessGrantEntity grant, Long operatorUserId) {
        if (!grant.getOperatorUserId().equals(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND);
        }
        return grant;
    }

    /** 本人、または保護者の承認が要る grant の保護者だけが操作できる。他人には存在を明かさない（001）。 */
    private SupportAccessGrantEntity findVisibleToUser(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return requireVisibleToUser(grant, userId);
    }

    private SupportAccessGrantEntity lockVisibleToUser(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findByIdForUpdate(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return requireVisibleToUser(grant, userId);
    }

    private static SupportAccessGrantEntity requireVisibleToUser(SupportAccessGrantEntity grant, Long userId) {
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
