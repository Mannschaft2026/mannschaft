package com.mannschaft.app.role.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 試験用: 運営アカウントの判定結果（{@link PlatformAccountAffiliationPolicy.Verdicts}）を組み立てる固定具（戦役 Z-A PR2）。
 *
 * <p>本番では判定結果を {@link PlatformAccountAffiliationPolicy#judge} だけが作る（取引の外で user_roles を読む）。
 * 他ドメインの Service を単体で試すときは、Controller が取引の外で済ませる判定の代わりに本固定具で判定結果を置き、
 * {@link PlatformAccountAffiliationPolicy#callJudged} の範囲で取引の入口を呼ぶ。</p>
 */
public final class PlatformAccountJudgedFixture {

    private PlatformAccountJudgedFixture() {
    }

    /** 指定ユーザーを運営アカウントと判定した結果。 */
    public static PlatformAccountAffiliationPolicy.Verdicts platform(Long... userIds) {
        return verdicts(true, userIds);
    }

    /** 指定ユーザーを一般利用者（運営アカウントでない）と判定した結果。 */
    public static PlatformAccountAffiliationPolicy.Verdicts general(Long... userIds) {
        return verdicts(false, userIds);
    }

    /** 指定ユーザーを一般利用者と判定した結果（コレクション版。モックの judge の応答に使う）。 */
    public static PlatformAccountAffiliationPolicy.Verdicts general(Collection<Long> userIds) {
        return userIds == null ? general() : general(userIds.toArray(Long[]::new));
    }

    /** 運営アカウント {@code platformUserIds} 以外を一般利用者と判定した結果（モックの judge の応答に使う）。 */
    public static PlatformAccountAffiliationPolicy.Verdicts judging(Collection<Long> userIds, Long... platformUserIds) {
        Map<Long, Boolean> map = new HashMap<>();
        java.util.Set<Long> platform = java.util.Set.of(platformUserIds);
        for (Long id : userIds) {
            if (id != null) {
                map.put(id, platform.contains(id));
            }
        }
        return new PlatformAccountAffiliationPolicy.Verdicts(map);
    }

    /** 判定結果を置いた範囲で {@code work} を実行する。 */
    public static <T> T judged(PlatformAccountAffiliationPolicy.Verdicts verdicts, Supplier<T> work) {
        return PlatformAccountAffiliationPolicy.callJudged(verdicts, work);
    }

    /** 判定結果を置いた範囲で {@code work} を実行する（戻り値なし）。 */
    public static void run(PlatformAccountAffiliationPolicy.Verdicts verdicts, Runnable work) {
        PlatformAccountAffiliationPolicy.runJudged(verdicts, work);
    }

    private static PlatformAccountAffiliationPolicy.Verdicts verdicts(boolean platform, Long... userIds) {
        Map<Long, Boolean> map = new HashMap<>();
        for (Long id : userIds) {
            if (id != null) {
                map.put(id, platform);
            }
        }
        return new PlatformAccountAffiliationPolicy.Verdicts(map);
    }
}
