package com.mannschaft.app.supportaccess.context;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * F10.9 代理閲覧の文脈をスレッドに保持する入口（試練スタブ）。
 *
 * <p>SupportAccessFilter が chain の前に {@link #set}、finally で {@link #clear} する。
 * 有効な間は DB 接続を READ ONLY にする（JdbcTemplate・@Modifying・ネイティブ SQL も拒否させるため）。
 * 閲覧記録・監査の REQUIRES_NEW だけは {@link #callWithWritableConnection} で書込可能な接続を使う。
 * {@code @Async} へは TaskDecorator（{@link SupportAccessContextTaskDecorator}）で伝播する。</p>
 */
public final class SupportAccessContextHolder {

    private SupportAccessContextHolder() {
    }

    public static void set(SupportAccessContext context) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public static Optional<SupportAccessContext> get() {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public static boolean isActive() {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    public static void clear() {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }

    /** 代理中でも閲覧記録・監査だけは書込可能な接続で実行する（REQUIRES_NEW の記録用）。 */
    public static <T> T callWithWritableConnection(Supplier<T> action) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
