package com.mannschaft.app.role.service;

import com.mannschaft.app.membership.domain.RoleKind;

import java.util.Optional;
import java.util.Set;

/**
 * 招待（共有リンク・個人招待）で付与できるロールと、付与の書き先を決める（CMP-261124-0157）。
 *
 * <p>設計（F01.2 03_business_logic.md「招待参加フロー」手順8・F00.5 §2.3）:</p>
 * <ul>
 *   <li>{@code user_roles} への INSERT は権限ロール（ADMIN / DEPUTY_ADMIN）の場合に限る。MEMBER / SUPPORTER の
 *       {@code user_roles} 行は V60.010 で廃止済み</li>
 *   <li>{@code memberships.role_kind} は MEMBER または SUPPORTER（SUPPORTER 招待なら SUPPORTER、それ以外は MEMBER）</li>
 * </ul>
 *
 * <p><b>暫定（MEMBER のみ）</b>: MEMBER は設計上 {@code memberships} だけでよいが、管理者による除名
 * （{@code RoleService#removeMember}）とロール変更（{@code RoleService#changeRole}）がまだ {@code user_roles} 行を前提に
 * しており（行が無いと ROLE_001。CMP-261020-1558）、MEMBER を所属のみにすると招待で入った人を除名・昇格できなくなる。
 * そのため MEMBER は従来どおり {@code user_roles} にも MEMBER 行を作る。両メソッドが所属のみの MEMBER を扱えるように
 * なった時点で {@code writesUserRole=false} に改める。SUPPORTER は従来から所属のみ（フォロー型の応援者と同じ）なので、
 * 設計どおり {@code user_roles} を作らない。</p>
 *
 * <p>招待で付与できるのは所属ロールの MEMBER / SUPPORTER だけ。ADMIN / DEPUTY_ADMIN は C-1（特権ロールの招待付与は
 * 権限昇格の経路になる）で発行・承諾とも 422 ROLE_009。GUEST は設計に招待参加時の書き先（memberships の role_kind が無い）
 * の定めが無く、SYSTEM_ADMIN はプラットフォームロールで招待対象外（03_business_logic.md 招待トークン作成フロー注記）なので、
 * どちらも安全側に倒して発行・承諾とも拒否する。</p>
 */
final class InviteRolePolicy {

    /** 招待で付与できるロール名。 */
    static final Set<String> INVITABLE_ROLE_NAMES = Set.of("MEMBER", "SUPPORTER");

    /** 付与の際に {@code user_roles} へ行を作る権限ロール名（招待以外の内部経路で渡されたときの書き先）。 */
    private static final Set<String> PERMISSION_ROLE_NAMES = Set.of("ADMIN", "DEPUTY_ADMIN");

    private InviteRolePolicy() {
    }

    /** 招待で付与できるロールか。 */
    static boolean isInvitable(String roleName) {
        return INVITABLE_ROLE_NAMES.contains(roleName);
    }

    /**
     * ロールを付与するときの書き先。所属ロール・権限ロールのどちらでもなければ空（付与してはならない）。
     *
     * @param roleName ロール名
     * @return 書き先（{@code memberships.role_kind} と {@code user_roles} 行の要否）
     */
    static Optional<GrantTarget> grantTarget(String roleName) {
        if ("SUPPORTER".equals(roleName)) {
            return Optional.of(new GrantTarget(RoleKind.SUPPORTER, false));
        }
        if ("MEMBER".equals(roleName)) {
            // 暫定: 除名・ロール変更が user_roles 行を前提にしているため MEMBER 行も作る（クラス javadoc）。
            return Optional.of(new GrantTarget(RoleKind.MEMBER, true));
        }
        if (PERMISSION_ROLE_NAMES.contains(roleName)) {
            return Optional.of(new GrantTarget(RoleKind.MEMBER, true));
        }
        return Optional.empty();
    }

    /**
     * 付与の書き先。
     *
     * @param roleKind         {@code memberships.role_kind}
     * @param writesUserRole   {@code user_roles} に当該ロールの行を作るか（権限ロールと、暫定で MEMBER）
     */
    record GrantTarget(RoleKind roleKind, boolean writesUserRole) {
    }
}
