package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.MemberWorkConstraintRequest;
import com.mannschaft.app.shift.dto.MemberWorkConstraintResponse;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link MemberWorkConstraintFacade} の単体テスト（CMP-261125-2114）。
 * 個別制約の upsert の認可（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）と対象の在籍検査が tx 本体より先に行われ、
 * 拒否したら tx 本体を呼ばないことを固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberWorkConstraintFacade 単体テスト")
class MemberWorkConstraintFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long ADMIN_ID = 10L;
    private static final Long TARGET_ID = 20L;
    private static final String TEAM = "TEAM";

    @Mock
    private MemberWorkConstraintService workConstraintService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TeamShiftSettingsService teamShiftSettingsService;

    private MemberWorkConstraintFacade facade;

    @BeforeEach
    void setUp() {
        facade = new MemberWorkConstraintFacade(workConstraintService,
                new ShiftManageAccessGate(accessControlService, teamShiftSettingsService), accessControlService);
    }

    private static MemberWorkConstraintRequest request() {
        return new MemberWorkConstraintRequest(new BigDecimal("100.00"), 22, 5, 8, new BigDecimal("11.00"), "メモ");
    }

    @Test
    @DisplayName("管理者かつ対象が在籍者なら tx 本体へ委譲する")
    void 管理者かつ在籍者なら委譲する() {
        MemberWorkConstraintRequest req = request();
        MemberWorkConstraintResponse expected = new MemberWorkConstraintResponse(
                null, TEAM_ID, TARGET_ID, null, null, null, null, null, null);
        given(accessControlService.hasAdminOrPermissionInScope(ADMIN_ID, TEAM_ID, TEAM, "MANAGE_SHIFTS"))
                .willReturn(true);
        given(accessControlService.isMember(ADMIN_ID, TEAM_ID, TEAM)).willReturn(true);
        given(accessControlService.isMember(TARGET_ID, TEAM_ID, TEAM)).willReturn(true);
        given(workConstraintService.upsertConstraint(TEAM_ID, TARGET_ID, req, ADMIN_ID)).willReturn(expected);

        assertThat(facade.upsertConstraint(TEAM_ID, TARGET_ID, req, ADMIN_ID)).isSameAs(expected);
    }

    @Test
    @DisplayName("権限が無ければ WORK_CONSTRAINT_FORBIDDEN。対象の在籍は引かず、tx 本体も呼ばない")
    void 権限なしは拒否し在籍を観測させない() {
        given(accessControlService.hasAdminOrPermissionInScope(ADMIN_ID, TEAM_ID, TEAM, "MANAGE_SHIFTS"))
                .willReturn(false);

        assertThatThrownBy(() -> facade.upsertConstraint(TEAM_ID, TARGET_ID, request(), ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN));
        verify(accessControlService, never()).isMember(any(), any(), any());
        verifyNoInteractions(workConstraintService);
    }

    @Test
    @DisplayName("対象が当該チームの在籍者でなければ（他チームの利用者）権限不足と同じ WORK_CONSTRAINT_FORBIDDEN")
    void 対象が在籍者でなければ拒否する() {
        given(accessControlService.hasAdminOrPermissionInScope(ADMIN_ID, TEAM_ID, TEAM, "MANAGE_SHIFTS"))
                .willReturn(true);
        given(accessControlService.isMember(ADMIN_ID, TEAM_ID, TEAM)).willReturn(true);
        given(accessControlService.isMember(TARGET_ID, TEAM_ID, TEAM)).willReturn(false);

        assertThatThrownBy(() -> facade.upsertConstraint(TEAM_ID, TARGET_ID, request(), ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN));
        verifyNoInteractions(workConstraintService);
    }

    private void givenManager() {
        given(accessControlService.hasAdminOrPermissionInScope(ADMIN_ID, TEAM_ID, TEAM, "MANAGE_SHIFTS"))
                .willReturn(true);
        given(accessControlService.isMember(ADMIN_ID, TEAM_ID, TEAM)).willReturn(true);
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN));
        verifyNoInteractions(workConstraintService);
    }

    @Test
    @DisplayName("退会済み（ロールだけ残り在籍の無い）ADMIN は拒否され、tx 本体を呼ばない")
    void 退会済みADMINは拒否する() {
        given(accessControlService.hasAdminOrPermissionInScope(ADMIN_ID, TEAM_ID, TEAM, "MANAGE_SHIFTS"))
                .willReturn(true);
        given(accessControlService.isMember(ADMIN_ID, TEAM_ID, TEAM)).willReturn(false);

        assertForbidden(() -> facade.upsertConstraint(TEAM_ID, TARGET_ID, request(), ADMIN_ID));
    }

    @Test
    @DisplayName("一覧・デフォルト upsert・削除 2 種: 管理者なら委譲、そうでなければ WORK_CONSTRAINT_FORBIDDEN で tx 本体を呼ばない")
    void その他の管理操作は認可後に委譲する() {
        givenManager();
        MemberWorkConstraintRequest req = request();
        facade.listConstraintsByTeam(TEAM_ID, ADMIN_ID);
        facade.upsertTeamDefault(TEAM_ID, req, ADMIN_ID);
        facade.deleteConstraint(TEAM_ID, TARGET_ID, ADMIN_ID);
        facade.deleteTeamDefault(TEAM_ID, ADMIN_ID);
        verify(workConstraintService).listConstraintsByTeam(TEAM_ID, ADMIN_ID);
        verify(workConstraintService).upsertTeamDefault(TEAM_ID, req, ADMIN_ID);
        verify(workConstraintService).deleteConstraint(TEAM_ID, TARGET_ID, ADMIN_ID);
        verify(workConstraintService).deleteTeamDefault(TEAM_ID, ADMIN_ID);
    }

    @Test
    @DisplayName("管理者でなければ一覧・デフォルト upsert・削除 2 種はいずれも拒否される")
    void その他の管理操作は管理者以外を拒否する() {
        // hasAdminOrPermissionInScope は未スタブ（false）。
        assertForbidden(() -> facade.listConstraintsByTeam(TEAM_ID, ADMIN_ID));
        assertForbidden(() -> facade.upsertTeamDefault(TEAM_ID, request(), ADMIN_ID));
        assertForbidden(() -> facade.deleteConstraint(TEAM_ID, TARGET_ID, ADMIN_ID));
        assertForbidden(() -> facade.deleteTeamDefault(TEAM_ID, ADMIN_ID));
    }
}
