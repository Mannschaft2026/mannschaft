package com.mannschaft.app.common.architecture.fixtures.entrygate;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;

/**
 * 番人 {@code MembersOnlyTeamEntryChokePointTest} の自己検査用。入口の関所（{@code MembershipService#join}）を通らずに
 * SUPPORTER 在籍の行を直接組み立てる「迂回する呼び出し」の見本（本番コードには置かない）。
 */
public class EntryGateRogueSupporterWriter {

    public MembershipEntity bypass(Long userId, Long teamId) {
        return MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(teamId)
                .roleKind(RoleKind.SUPPORTER)
                .build();
    }
}
