package com.mannschaft.app.role.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261107-1833 PR3: 招待の有効性判定（GET 時の期限判定 g・仮発行の窓 c）の単体試験。F-WIN・P1 の境界を固定する。
 */
@DisplayName("InviteValidity 単体テスト（CMP-261107-1833 PR3）")
class InviteValidityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 12, 0, 0);
    private static final LocalDateTime CREATED = NOW.minusDays(1);

    @Test
    @DisplayName("P1: 失効・期限・上限が無ければ VALID（失敗コードなし）")
    void valid() {
        InviteValidity v = InviteValidity.evaluate(null, CREATED, null, null, 0, NOW);
        assertThat(v).isEqualTo(InviteValidity.VALID);
        assertThat(v.failureCode()).isNull();
    }

    @Test
    @DisplayName("P1: expires_at ちょうどは有効、直後は EXPIRED（ROLE_002）")
    void expiryBoundary() {
        assertThat(InviteValidity.evaluate(null, CREATED, NOW, 3, 0, NOW)).isEqualTo(InviteValidity.VALID);
        InviteValidity after = InviteValidity.evaluate(null, CREATED, NOW, 3, 0, NOW.plusNanos(1_000));
        assertThat(after).isEqualTo(InviteValidity.EXPIRED);
        assertThat(after.failureCode()).isEqualTo("ROLE_002");
    }

    @Test
    @DisplayName("P1: 使用回数が上限に到達したら EXHAUSTED（ROLE_003）。上限未満は VALID、上限 NULL は無制限")
    void exhausted() {
        InviteValidity reached = InviteValidity.evaluate(null, CREATED, null, 1, 1, NOW);
        assertThat(reached).isEqualTo(InviteValidity.EXHAUSTED);
        assertThat(reached.failureCode()).isEqualTo("ROLE_003");
        assertThat(InviteValidity.evaluate(null, CREATED, null, 2, 1, NOW)).isEqualTo(InviteValidity.VALID);
        assertThat(InviteValidity.evaluate(null, CREATED, null, null, 99, NOW)).isEqualTo(InviteValidity.VALID);
    }

    @Test
    @DisplayName("P1: 通常の失効（revoked_at ≠ created_at）は REVOKED（ROLE_002）")
    void revoked() {
        InviteValidity v = InviteValidity.evaluate(NOW.minusMinutes(1), CREATED, null, 3, 0, NOW);
        assertThat(v).isEqualTo(InviteValidity.REVOKED);
        assertThat(v.failureCode()).isEqualTo("ROLE_002");
    }

    @Test
    @DisplayName("F-WIN: 仮発行の印（revoked_at = created_at）があり作成から 60 秒以内は STAGING（処理中・失敗コードなし）")
    void stagingWithinWindow() {
        LocalDateTime created = NOW.minusSeconds(59);
        InviteValidity v = InviteValidity.evaluate(created, created, NOW.plusDays(7), 1, 0, NOW);
        assertThat(v).isEqualTo(InviteValidity.STAGING);
        assertThat(v.failureCode()).isNull();
        LocalDateTime exactly = NOW.minusSeconds(60);
        assertThat(InviteValidity.evaluate(exactly, exactly, NOW.plusDays(7), 1, 0, NOW))
                .as("ちょうど 60 秒はまだ処理中").isEqualTo(InviteValidity.STAGING);
    }

    @Test
    @DisplayName("F-WIN: 仮発行の印があり 60 秒を過ぎたら REVOKED（ROLE_002）")
    void stagingAfterWindow() {
        LocalDateTime created = NOW.minusSeconds(61);
        InviteValidity v = InviteValidity.evaluate(created, created, NOW.plusDays(7), 1, 0, NOW);
        assertThat(v).isEqualTo(InviteValidity.REVOKED);
        assertThat(v.failureCode()).isEqualTo("ROLE_002");
        assertThat(InviteValidity.STAGING_WINDOW).hasSeconds(60);
    }
}
