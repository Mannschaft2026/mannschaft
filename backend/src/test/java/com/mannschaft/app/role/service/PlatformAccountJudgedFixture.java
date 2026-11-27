package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.UserRoleRepository;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 試験用: 運営アカウントの判定結果（{@link PlatformAccountAffiliationPolicy.Verdicts}）を組み立てる固定具（戦役 Z-A PR2）。
 *
 * <p>本番では判定結果を {@link PlatformAccountAffiliationPolicy#judge} だけが作る（取引の外で user_roles を読む）。
 * {@code Verdicts} のコンストラクタは private のため、本固定具も UserRoleRepository のモックを差した本物の
 * {@link PlatformAccountAffiliationPolicy#judge} を通して作る（運営の有無だけモックの応答で決める）。
 * 他ドメインの Service を単体で試すときは、Controller が取引の外で済ませる判定の代わりに本固定具で判定結果を置き、
 * {@link PlatformAccountAffiliationPolicy#callJudged} の範囲で取引の入口を呼ぶ。</p>
 */
public final class PlatformAccountJudgedFixture {

    private PlatformAccountJudgedFixture() {
    }

    /** 指定ユーザーを運営アカウントと判定した結果。 */
    public static PlatformAccountAffiliationPolicy.Verdicts platform(Long... userIds) {
        return judging(Arrays.asList(userIds), userIds);
    }

    /** 指定ユーザーを一般利用者（運営アカウントでない）と判定した結果。 */
    public static PlatformAccountAffiliationPolicy.Verdicts general(Long... userIds) {
        return judging(Arrays.asList(userIds));
    }

    /** 指定ユーザーを一般利用者と判定した結果（コレクション版。モックの judge の応答に使う）。 */
    public static PlatformAccountAffiliationPolicy.Verdicts general(Collection<Long> userIds) {
        return userIds == null ? general() : general(userIds.toArray(Long[]::new));
    }

    /** 運営アカウント {@code platformUserIds} 以外を一般利用者と判定した結果（モックの judge の応答に使う）。 */
    public static PlatformAccountAffiliationPolicy.Verdicts judging(Collection<Long> userIds, Long... platformUserIds) {
        Set<Long> platform = new HashSet<>(Arrays.asList(platformUserIds));
        UserRoleRepository repository = mock(UserRoleRepository.class);
        org.mockito.Mockito.lenient().when(repository.existsPlatformAdminRoleByUserId(any()))
                .thenAnswer(inv -> platform.contains((Long) inv.getArgument(0)) ? 1L : 0L);
        org.mockito.Mockito.lenient().when(repository.findPlatformAdminUserIdsIn(any()))
                .thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                        .filter(platform::contains).toList());
        return new PlatformAccountAffiliationPolicy(repository).judge(userIds == null ? List.of() : userIds);
    }

    /** 判定結果を置いた範囲で {@code work} を実行する。 */
    public static <T> T judged(PlatformAccountAffiliationPolicy.Verdicts verdicts, Supplier<T> work) {
        return PlatformAccountAffiliationPolicy.callJudged(verdicts, work);
    }

    /** 判定結果を置いた範囲で {@code work} を実行する（戻り値なし）。 */
    public static void run(PlatformAccountAffiliationPolicy.Verdicts verdicts, Runnable work) {
        PlatformAccountAffiliationPolicy.runJudged(verdicts, work);
    }
}
