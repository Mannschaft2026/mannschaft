package com.mannschaft.app.common.sysadminbypass;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.security.AccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR3（試練）: {@code @accessGuard.*}（{@link AccessGuard}）から SYSTEM_ADMIN の短絡を撤去する受け入れテスト。
 *
 * <p>AC2: 所属の無い SYSTEM_ADMIN に対し、4 メソッド（isScopeAdmin・isScopeStrictAdmin・isScopeMember・
 * hasScopePermission）はすべて false。判定は isAdminOrAbove / isAdmin / isMember / hasPermission に任せ、
 * SYSTEM_ADMIN かどうかを参照しない。ROLE_SYSTEM_ADMIN の authority を持っていても同じ。</p>
 *
 * <p>AC-兼任（回帰）: 所属のある一般利用者（ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER）の判定は撤去前と同じ。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR3: AccessGuard（@accessGuard.*）の SYSTEM_ADMIN 短絡撤去")
class AccessGuardSysAdminBypassRemovalTest {

    private static final Long SYSADMIN = 900L;
    private static final Long TEAM = 1L;
    private static final String TEAM_T = "TEAM";
    private static final String PERMISSION = "MANAGE_SCHEDULE";

    @Mock
    private AccessControlService acs;

    @InjectMocks
    private AccessGuard guard;

    private static Authentication auth(Long userId, String... roles) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @BeforeEach
    void stubPlatformAccountWithoutAffiliation() {
        given(acs.isSystemAdmin(SYSADMIN)).willReturn(true);
        given(acs.isAdminOrAbove(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        given(acs.isAdmin(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        given(acs.isMember(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        given(acs.hasPermission(SYSADMIN, TEAM, TEAM_T, PERMISSION)).willReturn(false);
    }

    @Nested
    @DisplayName("AC2: 所属の無い SYSTEM_ADMIN には 4 メソッドとも false")
    class SystemAdminWithoutAffiliation {

        @Test
        @DisplayName("isScopeAdmin は false")
        void isScopeAdmin_false() {
            assertThat(guard.isScopeAdmin(auth(SYSADMIN, "ROLE_SYSTEM_ADMIN"), TEAM, TEAM_T)).isFalse();
        }

        @Test
        @DisplayName("isScopeStrictAdmin（ADMIN 専用）は false")
        void isScopeStrictAdmin_false() {
            assertThat(guard.isScopeStrictAdmin(auth(SYSADMIN, "ROLE_SYSTEM_ADMIN"), TEAM, TEAM_T)).isFalse();
        }

        @Test
        @DisplayName("isScopeMember は false")
        void isScopeMember_false() {
            assertThat(guard.isScopeMember(auth(SYSADMIN, "ROLE_SYSTEM_ADMIN"), TEAM, TEAM_T)).isFalse();
        }

        @Test
        @DisplayName("hasScopePermission は false")
        void hasScopePermission_false() {
            assertThat(guard.hasScopePermission(auth(SYSADMIN, "ROLE_SYSTEM_ADMIN"), TEAM, TEAM_T, PERMISSION))
                    .isFalse();
        }

        @Test
        @DisplayName("Guard は SYSTEM_ADMIN かどうかを判定材料にしない（isSystemAdmin を一度も呼ばない）")
        void guardNeverConsultsIsSystemAdmin() {
            Authentication a = auth(SYSADMIN, "ROLE_SYSTEM_ADMIN");
            guard.isScopeAdmin(a, TEAM, TEAM_T);
            guard.isScopeStrictAdmin(a, TEAM, TEAM_T);
            guard.isScopeMember(a, TEAM, TEAM_T);
            guard.hasScopePermission(a, TEAM, TEAM_T, PERMISSION);
            verify(acs, never()).isSystemAdmin(anyLong());
        }
    }

    @Nested
    @DisplayName("AC-兼任（回帰）: 所属のある一般利用者の判定は撤去前と同じ")
    class AffiliatedUsersUnchanged {

        private static final Long ADMIN = 11L;
        private static final Long DEPUTY = 12L;
        private static final Long MEMBER = 13L;
        private static final Long SUPPORTER = 14L;

        @BeforeEach
        void stubAffiliatedUsers() {
            for (Long u : List.of(ADMIN, DEPUTY, MEMBER, SUPPORTER)) {
                given(acs.isSystemAdmin(u)).willReturn(false);
                given(acs.isMember(u, TEAM, TEAM_T)).willReturn(true);
            }
            given(acs.isAdminOrAbove(ADMIN, TEAM, TEAM_T)).willReturn(true);
            given(acs.isAdmin(ADMIN, TEAM, TEAM_T)).willReturn(true);
            given(acs.isAdminOrAbove(DEPUTY, TEAM, TEAM_T)).willReturn(true);
            given(acs.hasPermission(ADMIN, TEAM, TEAM_T, PERMISSION)).willReturn(true);
            given(acs.hasPermission(DEPUTY, TEAM, TEAM_T, PERMISSION)).willReturn(true);
        }

        @Test
        @DisplayName("ADMIN: 4 メソッドとも true")
        void adminAllTrue() {
            Authentication a = auth(ADMIN);
            assertThat(guard.isScopeAdmin(a, TEAM, TEAM_T)).isTrue();
            assertThat(guard.isScopeStrictAdmin(a, TEAM, TEAM_T)).isTrue();
            assertThat(guard.isScopeMember(a, TEAM, TEAM_T)).isTrue();
            assertThat(guard.hasScopePermission(a, TEAM, TEAM_T, PERMISSION)).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN: isScopeAdmin・isScopeMember・付与済み権限は true、isScopeStrictAdmin は false")
        void deputyKeepsExistingContract() {
            Authentication a = auth(DEPUTY);
            assertThat(guard.isScopeAdmin(a, TEAM, TEAM_T)).isTrue();
            assertThat(guard.isScopeStrictAdmin(a, TEAM, TEAM_T)).isFalse();
            assertThat(guard.isScopeMember(a, TEAM, TEAM_T)).isTrue();
            assertThat(guard.hasScopePermission(a, TEAM, TEAM_T, PERMISSION)).isTrue();
        }

        @Test
        @DisplayName("MEMBER・SUPPORTER: isScopeMember だけ true、管理系と未付与権限は false")
        void memberAndSupporterKeepExistingContract() {
            for (Long u : List.of(MEMBER, SUPPORTER)) {
                Authentication a = auth(u);
                assertThat(guard.isScopeMember(a, TEAM, TEAM_T)).isTrue();
                assertThat(guard.isScopeAdmin(a, TEAM, TEAM_T)).isFalse();
                assertThat(guard.isScopeStrictAdmin(a, TEAM, TEAM_T)).isFalse();
                assertThat(guard.hasScopePermission(a, TEAM, TEAM_T, PERMISSION)).isFalse();
            }
        }
    }
}
