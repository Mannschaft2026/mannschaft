package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
 */
@Component
@RequiredArgsConstructor
public class PlatformAccountAffiliationPolicy {

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
     * 公式村の作成者としての村長所属だけを許す、明示の例外（マスター決定 2026-11-26）。
     *
     * <p>{@code officialVillageCreator} が true（呼び出し側が「村の種別が OFFICIAL で、本人がその作成者として
     * 村長になる所属行」であることを確認済み）なら通し、false なら {@link #assertAffiliatable} と同じ判定にする。
     * 村の種別そのものはこのクラスでは確認しない（village ドメインへ依存しないため）。呼び出し側は
     * 公式村の作成経路（村作成申請）に限って true を渡すこと。</p>
     *
     * @param userId                所属させようとしているユーザー ID
     * @param officialVillageCreator 公式村の作成者を村長として所属させる場合だけ true
     */
    public void assertAffiliatableAsOfficialVillageCreator(Long userId, boolean officialVillageCreator) {
        if (officialVillageCreator) {
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
}
