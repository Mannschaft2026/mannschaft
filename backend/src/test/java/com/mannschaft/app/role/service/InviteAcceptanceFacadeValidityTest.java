package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.organization.service.OrganizationInviteQueryService;
import com.mannschaft.app.role.dto.InviteValidity;
import com.mannschaft.app.scopefolder.service.InviteFolderPlacementService;
import com.mannschaft.app.team.service.TeamInviteQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CMP-261107-1833 PR3 4 回目の検分 P2: 参照時の有効性判定で、失効の CAS が 0 行だった後の確定読みは
 * readOnly（replica に向かいうる）の経路を通らず、CAS と同じ読み書きの短TX（primary）で行う。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteAcceptanceFacade#findInviteValidity の確定読み（CMP-261107-1833 PR3）")
class InviteAcceptanceFacadeValidityTest {

    private static final String HASH = "h".repeat(64);
    private static final Long USER_ID = 41L;

    @Mock private UserRowLockService userRowLockService;
    @Mock private InviteAcceptanceWriter writer;
    @Mock private TeamInviteQueryService teamInviteQueryService;
    @Mock private OrganizationInviteQueryService organizationInviteQueryService;
    @Mock private InviteFolderPlacementService placementService;
    @Mock private MembershipGrantService membershipGrantService;
    @Mock private AuditLogService auditLogService;
    @Mock private SecretTokenVault vault;

    @org.mockito.Mock com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;
    @InjectMocks private InviteAcceptanceFacade facade;

    @Test
    @DisplayName("replica の遅延で最初の読みが古い「失効（仮発行のまま窓の外）」でも、primary の CAS と確定読みで VALID なら VALID。"
            + "readOnly の読み直しはしない")
    void staleReplicaReadIsSettledOnPrimary() {
        given(writer.findValidity(HASH, USER_ID)).willReturn(Optional.of(InviteValidity.REVOKED));
        given(writer.revokeExpiredStaged(HASH, USER_ID)).willReturn(Optional.of(InviteValidity.VALID));

        assertThat(facade.findInviteValidity(HASH, USER_ID)).contains(InviteValidity.VALID);

        verify(writer, times(1)).findValidity(HASH, USER_ID);
        verify(writer, times(1)).revokeExpiredStaged(HASH, USER_ID);
    }

    @Test
    @DisplayName("失効が勝てば REVOKED。失効と読めなかったものは CAS を打たない")
    void revokedWinsAndOthersSkipCas() {
        given(writer.findValidity(HASH, USER_ID)).willReturn(Optional.of(InviteValidity.REVOKED));
        given(writer.revokeExpiredStaged(HASH, USER_ID)).willReturn(Optional.of(InviteValidity.REVOKED));
        assertThat(facade.findInviteValidity(HASH, USER_ID)).contains(InviteValidity.REVOKED);

        given(writer.findValidity("v".repeat(64), USER_ID)).willReturn(Optional.of(InviteValidity.STAGING));
        assertThat(facade.findInviteValidity("v".repeat(64), USER_ID)).contains(InviteValidity.STAGING);
        verify(writer, never()).revokeExpiredStaged("v".repeat(64), USER_ID);
    }

    @Test
    @DisplayName("CAS と確定読みの入口は読み書きの @Transactional（ReplicaRoutingAspect が PRIMARY に固定する）で、readOnly ではない")
    void settlingPathIsPrimary() throws NoSuchMethodException {
        Transactional tx = InviteAcceptanceWriter.class.getMethod("revokeExpiredStaged", String.class, Long.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).as("メソッドレベルの @Transactional（ルーティングのポイントカット対象）").isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }
}
