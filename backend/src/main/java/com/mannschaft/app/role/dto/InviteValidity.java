package com.mannschaft.app.role.dto;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 招待トークンの有効性（CMP-261107-1833 PR3 設計 c・g）。保留の GET 時の期限判定と、承認時の「処理中」判定に使う。
 *
 * <ul>
 *   <li>{@link #VALID}: 有効（{@code expires_at} ちょうどは有効、直後は失効）</li>
 *   <li>{@link #STAGING}: 仮発行の印（{@code revoked_at = created_at}）があり、作成から {@link #STAGING_WINDOW} 以内（処理中）</li>
 *   <li>{@link #REVOKED}: 失効（仮発行の印があって窓を過ぎたものを含む）。ROLE_002</li>
 *   <li>{@link #EXPIRED}: 期限切れ。ROLE_002</li>
 *   <li>{@link #EXHAUSTED}: 使用回数の上限に到達。ROLE_003</li>
 * </ul>
 *
 * <p><b>試練スタブ</b>: 判定は出陣で実装する。</p>
 */
public enum InviteValidity {
    VALID,
    STAGING,
    REVOKED,
    EXPIRED,
    EXHAUSTED;

    /** 仮発行を「処理中」として扱う窓。 */
    public static final Duration STAGING_WINDOW = Duration.ofSeconds(60);

    /**
     * 招待トークンの列から有効性を判定する（時刻はすべて DB 格納基準の UTC 壁時計）。
     *
     * @param revokedAt 失効日時（仮発行中は created_at と同値）
     * @param createdAt 作成日時
     * @param expiresAt 有効期限（NULL は無期限）
     * @param maxUses   使用回数の上限（NULL は無制限）
     * @param usedCount 使用回数
     * @param now       判定時刻（DB の現在時刻）
     */
    public static InviteValidity evaluate(LocalDateTime revokedAt, LocalDateTime createdAt, LocalDateTime expiresAt,
                                          Integer maxUses, int usedCount, LocalDateTime now) {
        throw new UnsupportedOperationException("未実装: InviteValidity#evaluate（CMP-261107-1833 PR3）");
    }

    /** 保留へ記録する失敗コード（REVOKED/EXPIRED は ROLE_002、EXHAUSTED は ROLE_003、それ以外は null）。 */
    public String failureCode() {
        throw new UnsupportedOperationException("未実装: InviteValidity#failureCode（CMP-261107-1833 PR3）");
    }
}
