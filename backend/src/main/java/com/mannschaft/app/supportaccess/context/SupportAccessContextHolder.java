package com.mannschaft.app.supportaccess.context;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * F10.9 代理閲覧の文脈をスレッドに保持する入口。
 *
 * <p>SupportAccessFilter が chain の前に {@link #set}、finally で {@link #clear} する。
 * 有効な間は DB 接続を READ ONLY にする（JdbcTemplate・@Modifying・ネイティブ SQL も拒否させるため。
 * 接続側の強制は {@link #isReadOnlyEnforced()} を見る DataSource ラッパーが行う）。
 * 閲覧記録・監査の REQUIRES_NEW だけは {@link #callWithWritableConnection} で書込可能な接続を使う。
 * {@code @Async} へは TaskDecorator（{@link SupportAccessContextTaskDecorator}）で伝播する。</p>
 */
public final class SupportAccessContextHolder {

    private static final ThreadLocal<SupportAccessContext> CONTEXT = new ThreadLocal<>();

    /** {@link #callWithWritableConnection} のネスト深さ（0 なら読取専用を強制する）。 */
    private static final ThreadLocal<Integer> WRITABLE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private SupportAccessContextHolder() {
    }

    public static void set(SupportAccessContext context) {
        CONTEXT.set(Objects.requireNonNull(context, "context"));
    }

    public static Optional<SupportAccessContext> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static boolean isActive() {
        return CONTEXT.get() != null;
    }

    public static void clear() {
        CONTEXT.remove();
        WRITABLE_DEPTH.remove();
    }

    /** 代理中で、かつ書込可能な接続の例外区間の外か（DB 接続を READ ONLY にすべき状態か）。 */
    public static boolean isReadOnlyEnforced() {
        return isActive() && WRITABLE_DEPTH.get() == 0;
    }

    /** 代理中でも閲覧記録・監査だけは書込可能な接続で実行する（REQUIRES_NEW の記録用）。 */
    public static <T> T callWithWritableConnection(Supplier<T> action) {
        WRITABLE_DEPTH.set(WRITABLE_DEPTH.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = WRITABLE_DEPTH.get() - 1;
            if (depth <= 0) {
                WRITABLE_DEPTH.remove();
            } else {
                WRITABLE_DEPTH.set(depth);
            }
        }
    }
}
