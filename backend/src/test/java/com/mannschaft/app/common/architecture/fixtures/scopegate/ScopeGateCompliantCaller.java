package com.mannschaft.app.common.architecture.fixtures.scopegate;

import com.mannschaft.app.common.AccessControlService;

/** 番人の自己検査用ダミー: 門と書き込み用の別名だけを使う（違反として検出されてはならない）。 */
public class ScopeGateCompliantCaller {

    private final AccessControlService accessControlService;

    public ScopeGateCompliantCaller(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    public void read(Long userId, Long teamId) {
        accessControlService.checkScopeContentAccess(userId, teamId, "TEAM");
    }

    public void write(Long userId, Long teamId) {
        accessControlService.checkMembershipForWrite(userId, teamId, "TEAM");
    }
}
