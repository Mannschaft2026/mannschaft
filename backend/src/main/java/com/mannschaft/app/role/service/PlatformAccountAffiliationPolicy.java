package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 運営アカウント（SYSTEM_ADMIN）のテナント所属禁止を判定する唯一の窓口（戦役 Z-A PR2）。
 *
 * <p>マスター決定（2026-11-26）: 運営は運営専用アカウントを使い、個人アカウントとの併用を禁止する。
 * 運営アカウントはチーム・組織・委員会・村に所属せず、サポーター（フォロー）にもならない。
 * 所属行（memberships / user_roles のスコープ行 / committee_members / village_memberships /
 * サポーター申請）を作る経路と、運営アカウント宛ての招待・参加申請を作る経路は、
 * すべて本クラスを通して {@code MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN}（403）で拒否する。</p>
 *
 * <p><b>唯一の例外（マスター決定 2026-11-26）</b>: 公式村（{@code OFFICIAL}）に限り、運営アカウントが
 * <b>村の作成者として村長（HEADMAN）に所属すること</b>を許す（{@link #assertAffiliatableAsOfficialVillageCreator}）。
 * 例外の範囲は公式村の作成時の作成者所属だけで、運営アカウントが他の村・他の経路（招待受諾・参加申請・
 * 承認など）で所属することは引き続き禁止する。</p>
 *
 * <p>DI 循環を避けるため {@link UserRoleRepository} だけに依存する（設計書 z-a-design.md §2）。
 * 運営ロールの判定は {@code UserRoleRepository#existsPlatformAdminRoleByUserId} を使い、
 * 新しいフラグは作らない。</p>
 *
 * <p>判定は<b>アカウントの状態を問わない</b>（停止中・非 ACTIVE の運営アカウントも対象。殿の決定 2026-11-26）。
 * そのため {@code existsSystemAdminByUserId}（ACTIVE・未削除のみ）ではなく
 * {@code existsPlatformAdminRoleByUserId} を使う。</p>
 *
 * <h2>他ドメインの取引からの使い方（判定は取引の外、拒否は取引の中）</h2>
 * <p>判定は role ドメインの {@code user_roles} を読む。チーム・会員・村などの取引の中から
 * {@link #assertAffiliatable} を呼ぶと、その取引が role ドメインへ越境する（CLAUDE.md DB 設計の原則 #5・
 * 番人 D-3T）。そこで他ドメインは次の二段で使う（{@code VillageSubjectAuthorizationGuard} と同じ型）。</p>
 * <ol>
 *   <li><b>判定（取引の外）</b>: Controller・取引を持たない段取り役が {@link #judge} で対象ユーザーを判定し、
 *       {@link #callJudged} / {@link #runJudged} で判定結果を同じスレッドの呼び出し範囲に置いて
 *       取引の入口を呼ぶ。ここでは例外を投げない。</li>
 *   <li><b>拒否（取引の中の従来の位置）</b>: 所属行を作る側は {@link #assertJudgedAffiliatable} /
 *       {@link #isJudgedPlatformAccount} / {@link #assertJudgedAffiliatableAsOfficialVillageCreator}
 *       （static。DB を読まない）で判定結果を確かめ、運営アカウントなら 403 を投げる。
 *       認可の後に置いた判定は、認可の後のまま投げる（拒否の位置・応答は変わらない）。</li>
 * </ol>
 * <p>判定結果を引数で渡さないのは、取引の入口の署名を変えないため（署名は D-3T の凍結キーでもある。
 * {@code InviteClaimGrantScope} と同じ理由）。判定結果が無いまま取引の中の判定に来たら、判定漏れの
 * 経路として {@link IllegalStateException} で止める（素通りさせない）。判定の対象（ユーザー ID）は
 * 取引の中で変わらない値（本人・申請行の申請者など）に限る。role ドメインの中では従来どおり
 * {@link #assertAffiliatable} を直接呼んでよい。</p>
 */
@Component
@RequiredArgsConstructor
public class PlatformAccountAffiliationPolicy {

    /** 取引の外で済ませた判定結果（ユーザー ID → 運営アカウントか）。{@link #callJudged} の間だけ置く。 */
    private static final ThreadLocal<Map<Long, Boolean>> JUDGED = new ThreadLocal<>();

    private final UserRoleRepository userRoleRepository;

    /**
     * 指定ユーザーがテナントへ所属できることを表明する。運営アカウントなら
     * {@code BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN)} を投げる。
     *
     * @param userId 所属させようとしているユーザー ID
     */
    public void assertAffiliatable(Long userId) {
        if (isPlatformAccount(userId)) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        }
    }

    /**
     * 例外の述語: 「公式村（OFFICIAL）で、本人が作成者で、村長（HEADMAN）の所属行」か（マスター決定 2026-11-26）。
     *
     * <p>点検 SQL（{@code platform_account_affiliation/inspection.sql} の village_memberships 節の NOT EXISTS）と
     * 同じ条件で、次の対応にある: {@code officialVillage} = {@code v.type = 'OFFICIAL'} /
     * {@code villageCreatedByUserId == userId} = {@code v.created_by_user_id = vm.subject_id} /
     * {@code headman} = {@code vm.role = 'HEADMAN'}。条件を変えるときは Java と SQL を必ず同時に直すこと。
     * village ドメインへ依存しないため、村の種別・ロールは真偽値で受ける。</p>
     */
    public static boolean isOfficialVillageCreatorHeadman(
            boolean officialVillage, Long villageCreatedByUserId, Long userId, boolean headman) {
        return officialVillage && headman && userId != null && userId.equals(villageCreatedByUserId);
    }

    /**
     * 公式村の作成者としての村長所属だけを許す、明示の例外（マスター決定 2026-11-26）。
     * {@link #isOfficialVillageCreatorHeadman} が true なら通し、そうでなければ {@link #assertAffiliatable} と同じ判定にする。
     * 呼べるクラスは番人 G4 で village の 2 クラス（入村枠サービス・所属プロフィールサービス）に限っている。
     */
    public void assertAffiliatableAsOfficialVillageCreator(
            Long userId, boolean officialVillage, Long villageCreatedByUserId, boolean headman) {
        if (isOfficialVillageCreatorHeadman(officialVillage, villageCreatedByUserId, userId, headman)) {
            return;
        }
        assertAffiliatable(userId);
    }

    /**
     * 指定ユーザーが運営アカウントかを返す（後継候補の選出から除外する用途。例外を投げない判定）。
     *
     * @param userId 対象ユーザー ID
     * @return 運営アカウントなら true
     */
    public boolean isPlatformAccount(Long userId) {
        return userId != null && userRoleRepository.existsPlatformAdminRoleByUserId(userId) > 0;
    }

    // ------------------------------------------------------------------
    // 判定（取引の外）
    // ------------------------------------------------------------------

    /**
     * 指定ユーザーが運営アカウントかを判定する（取引の外で呼ぶ。例外を投げない）。結果は {@link #callJudged} /
     * {@link #runJudged} で他ドメインの取引の入口へ渡す。
     *
     * @param userIds 判定するユーザー ID（null は無視する）
     * @return 判定結果
     */
    public Verdicts judge(Collection<Long> userIds) {
        Set<Long> targets = new LinkedHashSet<>();
        for (Long userId : userIds) {
            if (userId != null) {
                targets.add(userId);
            }
        }
        Map<Long, Boolean> verdicts = new HashMap<>();
        if (targets.size() == 1) {
            // 1 件（本人の操作など大多数）は従来の単一照会のまま。
            Long only = targets.iterator().next();
            verdicts.put(only, isPlatformAccount(only));
        } else if (!targets.isEmpty()) {
            // 複数件（招集・一括承認など）は 1 本の IN 句にまとめる（1 件ずつ user_roles を引かない）。
            Set<Long> platformIds = new HashSet<>(userRoleRepository.findPlatformAdminUserIdsIn(targets));
            for (Long userId : targets) {
                verdicts.put(userId, platformIds.contains(userId));
            }
        }
        return new Verdicts(verdicts);
    }

    /**
     * 判定結果を同じスレッドの呼び出し範囲に置いて {@code work} を実行する。取引の外（Controller・取引を持たない
     * 段取り役・role ドメインの取引）から、他ドメインの取引の入口を呼ぶときに使う。本メソッドは例外を投げない
     * （拒否は取引の中の {@link #assertJudgedAffiliatable} が投げる）。入れ子にした場合は外側の判定結果に足し、
     * 終われば外側の状態へ戻す。同期呼び出し専用（別スレッドへは伝播しない）。
     *
     * <p>判定結果は {@link #judge} だけが作る（呼び出し側が「運営でない」と申告して素通りさせる道を作らない）。
     * null は判定結果なしとして扱い、取引の中の判定は {@link IllegalStateException} で止まる。</p>
     *
     * @param verdicts {@link #judge} の結果
     * @param work     判定結果を置いた範囲で実行する処理
     * @return {@code work} の戻り値
     */
    public static <T> T callJudged(Verdicts verdicts, Supplier<T> work) {
        Map<Long, Boolean> previous = JUDGED.get();
        Map<Long, Boolean> merged = previous == null ? new HashMap<>() : new HashMap<>(previous);
        if (verdicts != null) {
            merged.putAll(verdicts.byUserId);
        }
        JUDGED.set(Map.copyOf(merged));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                JUDGED.remove();
            } else {
                JUDGED.set(previous);
            }
        }
    }

    /** 戻り値の無い {@link #callJudged}。 */
    public static void runJudged(Verdicts verdicts, Runnable work) {
        callJudged(verdicts, () -> {
            work.run();
            return null;
        });
    }

    /**
     * {@link #judge} の判定結果（ユーザー ID → 運営アカウントか）。不変。
     * コンストラクタは private で、本クラスの {@link #judge} だけが作れる（同じ package の別クラスや試験からも作れない）。
     * 試験で判定結果が要るときは、UserRoleRepository のモックを渡した本クラスの {@link #judge} を通す。
     */
    public static final class Verdicts {
        private final Map<Long, Boolean> byUserId;

        private Verdicts(Map<Long, Boolean> byUserId) {
            this.byUserId = Map.copyOf(byUserId);
        }
    }

    // ------------------------------------------------------------------
    // 拒否（取引の中。DB を読まない）
    // ------------------------------------------------------------------

    /**
     * 取引の外で済ませた判定結果で、指定ユーザーがテナントへ所属できることを表明する
     * （{@link #assertAffiliatable} の取引内版）。運営アカウントなら
     * {@code BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN)} を投げる。
     *
     * @throws IllegalStateException 判定結果が呼び出し範囲に無い（取引の外での判定が漏れた経路）
     */
    public static void assertJudgedAffiliatable(Long userId) {
        if (judgedPlatformAccount(userId)) {
            throw new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        }
    }

    /**
     * 公式村の作成者としての村長所属だけを許す例外の取引内版（{@link #assertAffiliatableAsOfficialVillageCreator} と同じ判定）。
     * 例外に当たるときは判定結果を見ない。呼べるクラスは番人 G4 で village の 2 クラスに限っている。
     */
    public static void assertJudgedAffiliatableAsOfficialVillageCreator(
            Long userId, boolean officialVillage, Long villageCreatedByUserId, boolean headman) {
        if (isOfficialVillageCreatorHeadman(officialVillage, villageCreatedByUserId, userId, headman)) {
            return;
        }
        assertJudgedAffiliatable(userId);
    }

    /**
     * 取引の外で済ませた判定結果で、指定ユーザーが運営アカウントかを返す（{@link #isPlatformAccount} の取引内版）。
     *
     * @throws IllegalStateException 判定結果が呼び出し範囲に無い（取引の外での判定が漏れた経路）
     */
    public static boolean isJudgedPlatformAccount(Long userId) {
        return judgedPlatformAccount(userId);
    }

    private static boolean judgedPlatformAccount(Long userId) {
        if (userId == null) {
            return false;
        }
        Map<Long, Boolean> verdicts = JUDGED.get();
        Boolean verdict = verdicts == null ? null : verdicts.get(userId);
        if (verdict == null) {
            throw new IllegalStateException(
                    "運営アカウントの判定が取引の外で済んでいません（callJudged の範囲外）: userId=" + userId);
        }
        return verdict;
    }
}
