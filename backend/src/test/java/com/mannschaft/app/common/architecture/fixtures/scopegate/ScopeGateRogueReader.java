package com.mannschaft.app.common.architecture.fixtures.scopegate;

import com.mannschaft.app.common.AccessControlService;

/** 番人の自己検査用ダミー: 読み取りで checkMembership / isMember を直呼びする（違反として検出されるべき）。 */
public class ScopeGateRogueReader {

    private final AccessControlService accessControlService;

    public ScopeGateRogueReader(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    public void list(Long userId, Long teamId) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
    }

    public boolean peek(Long userId, Long teamId) {
        return java.util.Optional.of(teamId).map(id -> accessControlService.isMember(userId, id, "TEAM")).orElse(false);
    }
}
