package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.team.dto.DeputyShiftViewToggles;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftHourlyRateFacade}（時給の参照の認可。tx の外）の単体テスト（CMP-261125-2114）。
 *
 * <p>時給は金銭情報で、画面側で導線を隠すだけでは URL 直打ち・API 直叩きを防げない。BE が本当に弾いているかを固定する。
 * 判定の実体は実物の {@link ShiftManageAccessGate} を {@link AccessControlService} のモックの上に組んで検証する。
 * 応答の全体は {@code ShiftHourlyRateScopeContractIT} / {@code ShiftManagePermissionMatrixContractIT} が実 DB で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftHourlyRateFacade 認可 単体テスト")
class ShiftHourlyRateFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long ADMIN_ID = 30L;
    private static final Long MEMBER_ID = 20L;
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 16);

    @Mock
    private ShiftHourlyRateService hourlyRateService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TeamShiftSettingsService teamShiftSettingsService;

    private ShiftHourlyRateFacade facade;

    @BeforeEach
    void setUp() {
        ShiftManageAccessGate manageGate = new ShiftManageAccessGate(accessControlService, teamShiftSettingsService);
        facade = new ShiftHourlyRateFacade(hourlyRateService,
                new ShiftHourlyRateAuthorizer(accessControlService, manageGate), manageGate, accessControlService);
    }

    private void givenManager(Long userId) {
        given(accessControlService.isAdminOrAbove(userId, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.hasAdminOrPermissionInScope(userId, TEAM_ID, "TEAM", "MANAGE_SHIFTS"))
                .willReturn(true);
        org.mockito.Mockito.lenient().when(accessControlService.isMember(userId, TEAM_ID, "TEAM")).thenReturn(true);
    }

    /** MANAGE_SHIFTS 無しの DEPUTY_ADMIN（時給閲覧切替を引数で指定）。 */
    private void givenDeputyWithoutManage(Long userId, boolean hourlyRateView) {
        given(accessControlService.isAdminOrAbove(userId, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.hasAdminOrPermissionInScope(userId, TEAM_ID, "TEAM", "MANAGE_SHIFTS"))
                .willReturn(false);
        org.mockito.Mockito.lenient().when(accessControlService.isMember(userId, TEAM_ID, "TEAM")).thenReturn(true);
        given(teamShiftSettingsService.findDeputyViewToggles(TEAM_ID))
                .willReturn(new DeputyShiftViewToggles(false, hourlyRateView));
    }

    @Test
    @DisplayName("一般メンバーが他メンバーの時給を参照 → 403 で、時給を 1 件も読まない（存在オラクルも残さない）")
    void 他人の時給参照_一般メンバーは403() {
        given(accessControlService.isAdminOrAbove(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> facade.listHourlyRates(OTHER_USER_ID, TEAM_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
        assertThatThrownBy(() -> facade.getEffectiveRate(OTHER_USER_ID, TEAM_ID, BASE_DATE, MEMBER_ID))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(hourlyRateService);
    }

    @Test
    @DisplayName("本人の時給参照 → チームメンバーであることだけを要求する（管理者判定を撃たない）")
    void 本人参照はメンバーシップのみ要求() {
        given(hourlyRateService.listHourlyRates(USER_ID, TEAM_ID)).willReturn(List.of());

        assertThat(facade.listHourlyRates(USER_ID, TEAM_ID, USER_ID)).isEmpty();

        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        verify(accessControlService, never()).hasAdminOrPermissionInScope(any(), any(), any(), any());
        verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
    }

    @Test
    @DisplayName("ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN は他メンバーの時給を参照できる（対象の在籍も確認する）")
    void 管理者は他人の時給を参照できる() {
        givenManager(ADMIN_ID);
        given(hourlyRateService.listHourlyRates(OTHER_USER_ID, TEAM_ID)).willReturn(List.of());

        assertThat(facade.listHourlyRates(OTHER_USER_ID, TEAM_ID, ADMIN_ID)).isEmpty();

        verify(accessControlService).checkMembership(OTHER_USER_ID, TEAM_ID, "TEAM");
    }

    @Test
    @DisplayName("チームロールを持たない SYSTEM_ADMIN は短絡せず、時給を 1 件も読まない（SYSTEM_ADMIN 判定を撃たない）")
    void システム管理者は短絡で許可されない() {
        given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> facade.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_ID))
                .isInstanceOf(BusinessException.class);
        verify(accessControlService, never()).isSystemAdmin(any());
        verifyNoInteractions(hourlyRateService);
    }

    @Test
    @DisplayName("チーム一括: ADMIN は認可の後に在籍中メンバーの ID を解決して tx 本体へ渡す（脱退者の時給を返さない）")
    void チーム一括は在籍者を渡す() {
        givenManager(ADMIN_ID);
        given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(USER_ID));
        List<HourlyRateResponse> rates = List.of();
        given(hourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, List.of(USER_ID))).willReturn(rates);

        assertThat(facade.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_ID)).isSameAs(rates);
    }

    @Test
    @DisplayName("時給閲覧切替 ON の MANAGE_SHIFTS 無し DEPUTY_ADMIN はチーム一括・他メンバー単体とも閲覧できる")
    void 切替ONの副管理者は閲覧できる() {
        givenDeputyWithoutManage(ADMIN_ID, true);
        given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(USER_ID));
        given(hourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, List.of(USER_ID)))
                .willReturn(List.of());
        given(hourlyRateService.getEffectiveRate(USER_ID, TEAM_ID, BASE_DATE)).willReturn(null);

        assertThat(facade.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_ID)).isEmpty();
        assertThat(facade.getEffectiveRate(USER_ID, TEAM_ID, BASE_DATE, ADMIN_ID)).isNull();
    }

    @Test
    @DisplayName("時給閲覧切替 OFF（閲覧切替だけ ON を含む）の MANAGE_SHIFTS 無し DEPUTY_ADMIN は 403")
    void 切替OFFの副管理者は時給を見られない() {
        givenDeputyWithoutManage(ADMIN_ID, false);

        assertThatThrownBy(() -> facade.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_ID))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(hourlyRateService);
    }
}
