package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingInviteSnapshot;
import com.mannschaft.app.auth.entity.RegistrationPendingInviteEntity;
import com.mannschaft.app.auth.event.InviteReissueRequestedEvent;
import com.mannschaft.app.auth.repository.RegistrationPendingInviteRepository;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * registration_pending_invites の auth 短トランザクション群（CMP-261107-1833 設計 §1・§2・§9-4）。
 *
 * <p>書き込みは全て REQUIRED の短TXで、外側TX（登録の users 作成など）があればそれに参加する。
 * 状態遷移は条件付き UPDATE で行い、承認処理中（ACCEPTING）の完了・差し戻しは自分の attempt の世代に
 * 一致したときだけ効く（5 分を超えて取り直された古い attempt は何も書けない）。
 * 招待のハッシュはログに出さない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingInviteStore {

    /** 未終了（破棄の対象）の状態。 */
    static final List<String> OPEN_STATUSES = List.of(PendingInviteStatus.PENDING.name(),
            PendingInviteStatus.ACCEPTING.name(), PendingInviteStatus.EXPIRED.name(),
            PendingInviteStatus.REISSUE_REQUESTED.name());

    private final RegistrationPendingInviteRepository repository;
    private final DomainEventPublisher eventPublisher;
    /** 作成・更新時刻の基準（既定の UTC Clock。瞬間は Instant で持ち、エンティティは現在時刻を自ら取得しない）。 */
    private final Clock clock;

    /** 登録と同じトランザクションで PENDING 行を作る（ハッシュのみ保存）。 */
    @Transactional
    public void createPending(Long userId, String tokenHash, String scopeType, Long scopeId) {
        Instant now = Instant.now(clock);
        repository.saveAndFlush(RegistrationPendingInviteEntity.builder()
                .userId(userId)
                .tokenHash(tokenHash)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .status(PendingInviteStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("招待付き登録の保留を作成: userId={}, scopeType={}, scopeId={}", userId, scopeType, scopeId);
    }

    @Transactional(readOnly = true)
    public Optional<PendingInviteSnapshot> find(Long userId) {
        return repository.findByUserId(userId).map(PendingInviteStore::toSnapshot);
    }

    /**
     * PENDING、または 5 分を超えて止まった ACCEPTING を、新しい attempt で ACCEPTING にする条件付き UPDATE。
     * 取れたら attempt ID、取れなければ空。
     */
    @Transactional
    public Optional<UUID> tryStartAccepting(Long userId) {
        UUID attemptId = UuidV7.generate();
        if (repository.startAccepting(userId, hex(attemptId)) == 1) {
            return Optional.of(attemptId);
        }
        return Optional.empty();
    }

    /** ACCEPTING かつ自分の attempt のときだけ結果状態へ進める（attempt は NULL に戻す）。0 行なら false。 */
    @Transactional
    public boolean completeAttempt(Long userId, UUID attemptId, PendingInviteStatus result, String failureCode) {
        return repository.completeAttempt(userId, hex(attemptId), result.name(), failureCode) == 1;
    }

    /** ACCEPTING かつ自分の attempt のときだけ PENDING に戻す（原因不明の失敗）。0 行なら false。 */
    @Transactional
    public boolean revertAttempt(Long userId, UUID attemptId) {
        return repository.completeAttempt(userId, hex(attemptId), PendingInviteStatus.PENDING.name(), null) == 1;
    }

    /** 現状態が from のいずれかのときだけ to へ遷移する。0 行なら false。 */
    @Transactional
    public boolean transition(Long userId, Set<PendingInviteStatus> from, PendingInviteStatus to) {
        if (from.isEmpty()) {
            return false;
        }
        return repository.transition(userId, from.stream().map(Enum::name).toList(), to.name()) == 1;
    }

    /**
     * EXPIRED/REISSUE_REQUESTED で前回要請から 7 日超（または未要請）なら REISSUE_REQUESTED にして true。
     * 記録できたときだけ、同じトランザクションで {@link InviteReissueRequestedEvent} を発行する
     * （通知はコミット後に招待権限者へ非同期で配送される）。
     */
    @Transactional
    public boolean markReissueRequested(Long userId) {
        if (repository.markReissueRequested(userId) != 1) {
            return false;
        }
        RegistrationPendingInviteEntity row = repository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("再招待要請を記録した保留が引けない: userId=" + userId));
        eventPublisher.publish(new InviteReissueRequestedEvent(userId, row.getScopeType(), row.getScopeId()));
        log.info("再招待要請を記録: userId={}, scopeType={}, scopeId={}", userId, row.getScopeType(), row.getScopeId());
        return true;
    }

    /** 未終了（PENDING/ACCEPTING/EXPIRED/REISSUE_REQUESTED）の行を DISCARDED にする。更新件数を返す。 */
    @Transactional
    public int discardOpen(Long userId) {
        return repository.discardOpen(List.of(userId));
    }

    /** {@link #discardOpen(Long)} の複数利用者版（バッチ用。利用者ごとに問い合わせない）。 */
    @Transactional
    public int discardOpen(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return 0;
        }
        return repository.discardOpen(userIds);
    }

    /**
     * 再招待の確定（REISSUE_REQUESTED→PENDING）。id・スコープ一致・status=REISSUE_REQUESTED・依頼者 ACTIVE のときだけ効く
     * 条件付き UPDATE で、token_hash を新しいハッシュに替え、failure_code・accept_attempt_id・accepting_started_at・
     * reissue_requested_at を NULL に戻す。0 行なら false（競合で負けた）。
     *
     * <p><b>試練スタブ</b>（CMP-261107-1833 PR3 設計 c・e）: 出陣で実装する。</p>
     */
    public boolean confirmReissue(UUID pendingId, String scopeType, Long scopeId, String newTokenHash) {
        throw new UnsupportedOperationException("未実装: PendingInviteStore#confirmReissue（CMP-261107-1833 PR3）");
    }

    /**
     * 再招待の見送り（REISSUE_REQUESTED→REISSUE_DECLINED）。条件付き UPDATE で、0 行なら false。
     * 記録できたら同じトランザクションで見送りのイベントを発行する。
     *
     * <p><b>試練スタブ</b>（CMP-261107-1833 PR3 設計 e）: 出陣で実装する。</p>
     */
    public boolean declineReissue(UUID pendingId, String scopeType, Long scopeId) {
        throw new UnsupportedOperationException("未実装: PendingInviteStore#declineReissue（CMP-261107-1833 PR3）");
    }

    private static PendingInviteSnapshot toSnapshot(RegistrationPendingInviteEntity row) {
        return new PendingInviteSnapshot(row.getId(), row.getUserId(), row.getTokenHash(), row.getScopeType(),
                row.getScopeId(), row.getStatus(), row.getFailureCode(), row.getAcceptingStartedAt(),
                row.getReissueRequestedAt());
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }
}
