package com.mannschaft.app.common;

import com.mannschaft.app.team.TeamErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 入口の関所の判定（CMP-261124-0159・F01.2 03「入口の関所」・F00 §20.6）。
 *
 * <p>不変条件「非公開チーム（{@code teams.visibility = MEMBERS_AND_ABOVE}）には在籍中のサポーターと GUEST が 0 件、
 * 有効な SUPPORTER 招待も 0 件」を、在籍・ロール・招待を作る最下層（関所）と各入口で守るための部品。
 * 判定は呼び出し元のトランザクションの中で、チーム行を {@code FOR SHARE} で読んでから行う。非公開化
 * （チーム行の排他ロック）と直列化され、「非公開化の直後に古い判定で在籍が作られる」競合を起こさない。</p>
 *
 * <h2>ロック順の規則（F01.2 03「並行・二重実行」）</h2>
 * <p>各トランザクションで、非公開化のトランザクションも取る行（memberships・user_roles・invite_tokens・
 * supporter_applications ほか）より<b>前に</b>本部品を呼ぶ。非公開化が取らない行（users・invite_token_claims）の
 * 後でよい。招待承諾は grant TX（claim のロックの後・在籍付与の前）で呼び、claim TX では呼ばない。</p>
 *
 * <p>拒否は 403 {@code TEAM_073}（PR-01 で登録済み。新しいコードを作らない）。判定順は「既存の認可 → 公開範囲」で、
 * 本部品は認可を通った後にだけ呼ぶ（権限の無い者には従来の拒否を返す）。</p>
 *
 * <p>チームの公開範囲は team ドメインが持つが、判定材料（主キーと {@code visibility} の 1 列）の読み取りは
 * 共通の可視性基盤と同じく common に置く（{@code MembersOnlyTeamQueryService} と同じ理由。各入口のドメインから
 * team の Repository を直接参照させない）。書き込みはしない。</p>
 */
@Component
public class MembersOnlyTeamEntryGuard {

    /** 非公開チームでは付与させないロール（在籍のサポーター・ゲスト）。 */
    private static final Set<String> SUPPORTER_OR_GUEST_ROLE_NAMES = Set.of("SUPPORTER", "GUEST");

    private static final String MEMBERS_ONLY = "MEMBERS_AND_ABOVE";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * チーム行を {@code FOR SHARE} で読み、非公開チームなら true。チームが無ければ false（不在の判定は各経路の既存処理に任せる）。
     *
     * <p>呼び出し元のトランザクションが必須（{@code MANDATORY}）。ロックはそのトランザクションの終わりまで保持される。
     * 判定の前に永続化コンテキストの他の保留中の書き込みを flush しない（{@code teams} だけを同期対象にする）ので、
     * ロック順の規則を崩さない。</p>
     *
     * @param teamId チーム ID
     * @return 非公開チームなら true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndCheckMembersOnly(Long teamId) {
        if (teamId == null) {
            return false;
        }
        List<?> rows = entityManager.createNativeQuery("SELECT visibility FROM teams WHERE id = :id FOR SHARE")
                .setParameter("id", teamId)
                .unwrap(NativeQuery.class)
                .addSynchronizedQuerySpace("teams")
                .getResultList();
        return !rows.isEmpty() && MEMBERS_ONLY.equals(String.valueOf(rows.get(0)));
    }

    /**
     * チーム行を {@code FOR SHARE} で読み、非公開チームなら 403 {@code TEAM_073} を投げる。
     *
     * @param teamId チーム ID
     * @throws BusinessException 非公開チームのとき（TEAM_073）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertSupporterEntryAllowed(Long teamId) {
        if (lockAndCheckMembersOnly(teamId)) {
            throw rejection();
        }
    }

    /** 非公開チームへのサポーター・ゲストの追加の拒否（403 {@code TEAM_073}）。 */
    public static BusinessException rejection() {
        return new BusinessException(TeamErrorCode.TEAM_073);
    }

    /** 非公開チームでは付与させないロール（SUPPORTER / GUEST）か。 */
    public static boolean isSupporterOrGuestRole(String roleName) {
        return roleName != null && SUPPORTER_OR_GUEST_ROLE_NAMES.contains(roleName);
    }
}
