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
     * 仮発行の窓の判定（SQL 断片・唯一の定義。CMP-261107-1833 PR3 検分 P2-2）。保留の参照時の判定
     * （{@code InviteTokenRepositoryCustom#findValidityByTokenHash}）と有効化の条件付き UPDATE
     * （{@code #activateStaged}）が同じ断片を使い、同じ 6 桁精度の DB 時刻で比べる。作成からちょうど 60 秒は窓の内側。
     * {@link #evaluate(LocalDateTime, LocalDateTime, LocalDateTime, Integer, int, LocalDateTime)} の Java 判定と同値。
     */
    public static final String STAGING_OPEN_SQL =
            "(revoked_at = created_at AND created_at >= UTC_TIMESTAMP(6) - INTERVAL 60 SECOND)";

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
        if (revokedAt != null) {
            boolean staged = revokedAt.equals(createdAt);
            if (staged && createdAt != null && !createdAt.plus(STAGING_WINDOW).isBefore(now)) {
                return STAGING;
            }
            return REVOKED;
        }
        if (expiresAt != null && expiresAt.isBefore(now)) {
            return EXPIRED;
        }
        if (maxUses != null && usedCount >= maxUses) {
            return EXHAUSTED;
        }
        return VALID;
    }

    /**
     * 仮発行の窓を DB 側（{@link #STAGING_OPEN_SQL}）で判定済みのときの版。失効していて窓の内側なら STAGING、
     * 外側なら REVOKED。それ以外の判定は {@link #evaluate(LocalDateTime, LocalDateTime, LocalDateTime, Integer, int, LocalDateTime)} と同じ。
     */
    public static InviteValidity evaluate(LocalDateTime revokedAt, boolean stagingOpen, LocalDateTime expiresAt,
                                          Integer maxUses, int usedCount, LocalDateTime now) {
        if (revokedAt != null) {
            return stagingOpen ? STAGING : REVOKED;
        }
        return evaluate(null, null, expiresAt, maxUses, usedCount, now);
    }

    /** 保留へ記録する失敗コード（REVOKED/EXPIRED は ROLE_002、EXHAUSTED は ROLE_003、それ以外は null）。 */
    public String failureCode() {
        return switch (this) {
            case REVOKED, EXPIRED -> "ROLE_002";
            case EXHAUSTED -> "ROLE_003";
            case VALID, STAGING -> null;
        };
    }
}
