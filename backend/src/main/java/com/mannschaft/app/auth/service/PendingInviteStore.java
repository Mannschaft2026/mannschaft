package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingInviteSnapshot;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * registration_pending_invites の auth 短トランザクション群（CMP-261107-1833 設計 §2・§9-4）。
 *
 * <p><b>試練スタブ</b>: 契約（メソッド署名）だけを置いた。振る舞いは出陣で実装する。</p>
 */
public class PendingInviteStore {

    /** 登録と同じトランザクションで PENDING 行を作る（ハッシュのみ保存）。 */
    public void createPending(Long userId, String tokenHash, String scopeType, Long scopeId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    public Optional<PendingInviteSnapshot> find(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    /**
     * PENDING、または 5 分を超えて止まった ACCEPTING を、新しい attempt で ACCEPTING にする条件付き UPDATE。
     * 取れたら attempt ID、取れなければ空。
     */
    public Optional<UUID> tryStartAccepting(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    /** ACCEPTING かつ自分の attempt のときだけ結果状態へ進める（attempt は NULL に戻す）。0 行なら false。 */
    public boolean completeAttempt(Long userId, UUID attemptId, PendingInviteStatus result, String failureCode) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    /** ACCEPTING かつ自分の attempt のときだけ PENDING に戻す（原因不明の失敗）。0 行なら false。 */
    public boolean revertAttempt(Long userId, UUID attemptId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    /** 現状態が from のいずれかのときだけ to へ遷移する。0 行なら false。 */
    public boolean transition(Long userId, Set<PendingInviteStatus> from, PendingInviteStatus to) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    /** EXPIRED/REISSUE_REQUESTED で前回要請から 7 日超（または未要請）なら REISSUE_REQUESTED にして true。 */
    public boolean markReissueRequested(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    /** 未終了（PENDING/ACCEPTING/EXPIRED/REISSUE_REQUESTED）の行を DISCARDED にする。更新件数を返す。 */
    public int discardOpen(Long userId) {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }
}
