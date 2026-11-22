package com.mannschaft.app.role.service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 招待承諾のロール付与（T7）に、付与の元になった claim を渡すための呼び出し範囲（CMP-261107-1833・Codex 検分 P1/P2 の根治）。
 *
 * <p>{@link InviteAcceptanceFacade} は {@link #call} の中で {@link MembershipGrantService#grantRole} を呼び、
 * {@code grantRole} は同じスレッド・同じトランザクションの中で {@link #current()} の claim をロックして判定し、
 * 付与したときだけ claim に「付与コミット済み」の印を立てる。こうして付与と印が同じトランザクションでコミット
 * （またはロールバック）され、共有中の claim を戻すか確定するかを推測なしに決められる。</p>
 *
 * <p>引数で渡さないのは、{@code grantRole} の署名と {@code @Transactional} 入口を変えないため
 * （署名は CMP-260826-1457 §7/§11 の不変条件で、D-3T の凍結キーでもある。新しい越境 TX 入口も作らない）。
 * 範囲は {@link #call} の間だけで、終われば必ず元に戻す。同期呼び出し専用（別スレッドへは伝播しない）。</p>
 */
final class InviteClaimGrantScope {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private InviteClaimGrantScope() {
    }

    /** {@code claimId} を付与の元として {@code work} を実行する。 */
    static <T> T call(UUID claimId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        CURRENT.set(claimId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 実行中の付与の元になった claim。範囲外なら空。 */
    static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
