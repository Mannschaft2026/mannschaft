package com.mannschaft.app.role.dto;

/**
 * 再招待で仮発行した宛先付きトークン（CMP-261107-1833 PR3 設計 c）。
 *
 * <p>仮発行は {@code revoked_at = created_at}（仮発行の印）で作り、平文は破棄する。保留の確定（auth）で
 * 新しいハッシュを保留へ書くため、ハッシュだけを持つ。ハッシュはログ・応答に出さない（{@link #toString()} で伏せる）。</p>
 */
public record StagedInviteToken(Long tokenId, String tokenHash) {

    @Override
    public String toString() {
        return "StagedInviteToken[tokenId=" + tokenId + ", tokenHash=***]";
    }
}
