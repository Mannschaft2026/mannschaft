package com.mannschaft.app.resident.transitivetx;

import com.mannschaft.app.common.ScopeContentAccessGate;
import com.mannschaft.app.common.transitivetx.CommonConstructorBridge;

import org.springframework.transaction.annotation.Transactional;

/** D-3T メタテスト専用 fixture。本物の共通の門（F00 §20）を呼ぶ入口と、ほかの common 中継を呼ぶ入口。 */
public class GateBoundaryFixture {
    private final ScopeContentAccessGate gate;

    public GateBoundaryFixture(ScopeContentAccessGate gate) {
        this.gate = gate;
    }

    @Transactional
    public void readThroughGate(Long userId, Long scopeId) {
        gate.checkScopeContentAccess(userId, scopeId, "TEAM");
        gate.filterAccessibleTeamIds(userId, java.util.List.of(scopeId));
    }

    @Transactional
    public void throughOtherCommonRelay() {
        new CommonConstructorBridge();
    }
}
