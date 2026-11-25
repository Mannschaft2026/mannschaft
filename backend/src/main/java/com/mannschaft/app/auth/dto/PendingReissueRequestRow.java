package com.mannschaft.app.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 再招待依頼の一覧・プレビューの 1 行（auth 内部の射影。CMP-261107-1833 PR3 設計 f）。
 *
 * <p>{@code tokenHash} は元の招待のハッシュで、付与ロールの判定にだけ使う。応答・ログに出してはならない。</p>
 */
public record PendingReissueRequestRow(UUID id, Long userId, String tokenHash, Instant requestedAt) {

    @Override
    public String toString() {
        return "PendingReissueRequestRow[id=" + id + ", userId=" + userId + ", tokenHash=***, requestedAt="
                + requestedAt + "]";
    }
}
