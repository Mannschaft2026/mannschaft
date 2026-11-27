package com.mannschaft.app.common.architecture.fixtures.entrygate;

import com.mannschaft.app.role.entity.UserRoleEntity;

import java.util.List;

/**
 * 番人 {@code MembersOnlyTeamEntryChokePointTest} の自己検査用。入口の関所（{@code RoleService#assignRole} /
 * {@code #changeRole}）を通らずに user_roles の行（GUEST 付与になりうる）を組み立てる見本（本番コードには置かない）。
 * ラムダの中で組み立てても外側のメソッドとして検出されることを確かめる。
 */
public class EntryGateRogueGuestWriter {

    public List<UserRoleEntity> bypass(Long userId, Long teamId, Long guestRoleId) {
        return List.of(userId).stream()
                .map(id -> UserRoleEntity.builder().userId(id).roleId(guestRoleId).teamId(teamId).build())
                .toList();
    }
}
