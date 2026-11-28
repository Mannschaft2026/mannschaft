package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeContentAccessGate;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261124-0159 PR-D1: {@link BulletinAccessGuard} が TEAM / ORGANIZATION の在籍確認を共通の門へ委ね、
 * AccessControlService の checkMembership / isMember を直接呼ばないことの単体検証。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinAccessGuard 共通の門への委譲（CMP-261124-0159 PR-D1）")
class BulletinAccessGuardScopeContentGateTest {

    @Mock private AccessControlService accessControlService;
    @Mock private RoleService roleService;
    @Mock private ScopeContentAccessGate scopeContentAccessGate;
    @InjectMocks private BulletinAccessGuard guard;

    @Test
    @DisplayName("V-0159-10-3: 読み取りは門の checkScopeContentAccess、書き込みは checkMembershipForWrite に委ね、拒否は 403 COMMON_002 のまま")
    void 読み取りと書き込みは門へ委譲する() {
        guard.checkScopeContentAccess(1L, ScopeType.TEAM, 10L);
        verify(scopeContentAccessGate).checkScopeContentAccess(1L, 10L, "TEAM");
        guard.checkMembershipForWrite(1L, ScopeType.ORGANIZATION, 20L);
        verify(scopeContentAccessGate).checkMembershipForWrite(1L, 20L, "ORGANIZATION");
        verify(scopeContentAccessGate, never()).checkMembershipForWrite(1L, 10L, "TEAM");

        willThrow(new BusinessException(CommonErrorCode.COMMON_002)).given(scopeContentAccessGate)
                .checkScopeContentAccess(2L, 10L, "TEAM");
        assertThatThrownBy(() -> guard.checkScopeContentAccess(2L, ScopeType.TEAM, 10L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("V-0159-10-3: 村・null のスコープは門に問わず拒否（fail-closed）、PERSONAL は本人のみ")
    void 判定できないスコープは拒否する() {
        assertThatThrownBy(() -> guard.checkScopeContentAccess(1L, ScopeType.VILLAGE, 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.checkMembershipForWrite(1L, null, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.checkScopeContentAccess(1L, ScopeType.PERSONAL, 2L))
                .isInstanceOf(BusinessException.class);
        guard.checkMembershipForWrite(1L, ScopeType.PERSONAL, 1L);
        verifyNoInteractions(scopeContentAccessGate);
    }
}
