package com.mannschaft.app.common.architecture.fixtures.scopegate;

import com.mannschaft.app.common.ScopeContentAccessGate;

/** 番人の自己検査用ダミー: 共通の門と書き込み用の別名だけを使う（違反として検出されてはならない）。 */
public class ScopeGateCompliantCaller {

    private final ScopeContentAccessGate scopeContentAccessGate;

    public ScopeGateCompliantCaller(ScopeContentAccessGate scopeContentAccessGate) {
        this.scopeContentAccessGate = scopeContentAccessGate;
    }

    public void read(Long userId, Long teamId) {
        scopeContentAccessGate.checkScopeContentAccess(userId, teamId, "TEAM");
    }

    public void write(Long userId, Long teamId) {
        scopeContentAccessGate.checkMembershipForWrite(userId, teamId, "TEAM");
    }
}
