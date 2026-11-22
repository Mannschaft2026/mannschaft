package com.mannschaft.app.role.dto;

import java.util.UUID;

/**
 * ロック付きで読んだ招待 claim の状態（CMP-261107-1833・Codex 検分 P1/P2 の根治）。
 *
 * @param claimId        claim の主キー
 * @param status         {@code CLAIMED} / {@code GRANTED} / {@code RELEASED}
 * @param grantCommitted この claim によるロール付与（T7）がコミット済みか（{@code grant_committed_at} の有無）。
 *                       付与と同じトランザクションで立つので、立っていればこの claim は参加に使われている
 * @param grantUnknown   付与印の導入前から CLAIMED だった旧データで、付与されたか不明か
 *                       （{@code legacy_grant_unknown_at} の有無）。自動では戻さない
 */
public record InviteClaimLock(UUID claimId, String status, boolean grantCommitted, boolean grantUnknown) {

    /** 新しい claim（旧データの印なし）。 */
    public InviteClaimLock(UUID claimId, String status, boolean grantCommitted) {
        this(claimId, status, grantCommitted, false);
    }

    /** 状態が CLAIMED（確定も補償もまだ）か。 */
    public boolean claimed() {
        return "CLAIMED".equals(status);
    }
}
