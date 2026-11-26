package com.mannschaft.app.role.service;

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
 * <p>DI 循環を避けるため {@link UserRoleRepository} だけに依存する（設計書 z-a-design.md §2）。
 * 運営ロールの判定は既存の {@code UserRoleRepository#existsSystemAdminByUserId} を使い、
 * 新しいフラグは作らない。</p>
 *
 * <p><b>試練（テスト先行）のスタブ</b>: 中身は実装（出陣）で埋める。現状は
 * {@link UnsupportedOperationException} を投げ、受け入れテストを red に保つ。</p>
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
        throw new UnsupportedOperationException("試練スタブ: 戦役 Z-A PR2 で実装する");
    }

    /**
     * 指定ユーザーが運営アカウントかを返す（後継候補の選出から除外する用途。例外を投げない判定）。
     *
     * @param userId 対象ユーザー ID
     * @return 運営アカウントなら true
     */
    public boolean isPlatformAccount(Long userId) {
        throw new UnsupportedOperationException("試練スタブ: 戦役 Z-A PR2 で実装する");
    }
}
