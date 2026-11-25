package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.PendingInviteStatus;

import java.util.UUID;

/**
 * 再招待・見送りの操作対象の保留（auth 内部の射影。CMP-261107-1833 PR3 設計 e）。
 *
 * <p>{@code tokenHash} は元の招待のハッシュで、付与ロールの判定にだけ使う。応答・ログに出してはならない。
 * {@code requesterActive} は依頼者が ACTIVE かつ論理削除されていないこと。</p>
 */
public record PendingReissueTarget(UUID id, Long userId, String tokenHash, PendingInviteStatus status,
                                   boolean requesterActive) {

    @Override
    public String toString() {
        return "PendingReissueTarget[id=" + id + ", userId=" + userId + ", tokenHash=***, status=" + status
                + ", requesterActive=" + requesterActive + "]";
    }
}
