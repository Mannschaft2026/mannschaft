package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.dashboard.ViewerRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-A PR4（試練）: ダッシュボードの {@link RoleResolver} から SYSTEM_ADMIN の短絡を撤去する受け入れテスト（AC7）。
 *
 * <p>運営アカウント（所属なし）のダッシュボードのロールは実ロール（所属なし＝PUBLIC）になる。
 * canResolve は isActiveUser だけで決まる。列挙値 {@code ViewerRole.SYSTEM_ADMIN} の削除は Z-C なので、
 * 本テストは名前で比較する（列挙値が消えてもコンパイルできる）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: dashboard RoleResolver の SYSTEM_ADMIN 短絡撤去（AC7）")
class RoleResolverSysAdminBypassRemovalTest {

    @Mock private AccessControlService accessControlService;
    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @InjectMocks private RoleResolver resolver;

    private static final Long SYSADMIN = 900L;
    private static final ScopeKey TEAM = new ScopeKey("TEAM", 2L);
    private static final ScopeKey OTHER_TEAM = new ScopeKey("TEAM", 3L);

    @Test
    @DisplayName("resolveViewerRolesFresh: 所属の無い運営アカウントは PUBLIC")
    void fresh_platformAccount_public() {
        given(membershipBatchQueryService.snapshotForUser(SYSADMIN, Set.of(TEAM), Set.of()))
                .willReturn(new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of()));
        given(accessControlService.isActiveUser(SYSADMIN)).willReturn(true);

        assertThat(resolver.resolveViewerRolesFresh(SYSADMIN, Set.of(TEAM)).get(TEAM)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    @DisplayName("resolveViewerRolesFresh: 所属のある SYSTEM_ADMIN（移行前の兼任）は実ロール、所属外は PUBLIC")
    void fresh_sysAdminWithAffiliation_realRole() {
        given(membershipBatchQueryService.snapshotForUser(SYSADMIN, Set.of(TEAM, OTHER_TEAM), Set.of()))
                .willReturn(new UserScopeRoleSnapshot(true, Map.of(TEAM, "MEMBER"), Map.of(), Set.of(), Set.of()));
        given(accessControlService.isActiveUser(SYSADMIN)).willReturn(true);

        Map<ScopeKey, ViewerRole> roles = resolver.resolveViewerRolesFresh(SYSADMIN, Set.of(TEAM, OTHER_TEAM));
        assertThat(roles.get(TEAM)).isEqualTo(ViewerRole.MEMBER);
        assertThat(roles.get(OTHER_TEAM)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    @DisplayName("resolveViewerRolesFresh: canResolve は isActiveUser だけ（非アクティブなら SYSTEM_ADMIN でも PUBLIC）")
    void fresh_inactivePlatformAccount_public() {
        given(membershipBatchQueryService.snapshotForUser(SYSADMIN, Set.of(TEAM), Set.of()))
                .willReturn(new UserScopeRoleSnapshot(true, Map.of(TEAM, "ADMIN"), Map.of(), Set.of(), Set.of()));
        given(accessControlService.isActiveUser(SYSADMIN)).willReturn(false);

        assertThat(resolver.resolveViewerRolesFresh(SYSADMIN, Set.of(TEAM)).get(TEAM)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    @DisplayName("resolveViewerRole（キャッシュ経路）: 所属の無い運営アカウントは PUBLIC（getRoleName の実ロールに従う）")
    void cached_platformAccount_public() {
        given(accessControlService.isSystemAdmin(SYSADMIN)).willReturn(true);
        given(accessControlService.getRoleName(SYSADMIN, 2L, "TEAM")).willReturn(null);

        assertThat(resolver.resolveViewerRole(SYSADMIN, "TEAM", 2L).name()).isNotEqualTo("SYSTEM_ADMIN");
        assertThat(resolver.resolveViewerRole(SYSADMIN, "TEAM", 2L)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    @DisplayName("resolveViewerRoleForPreview: 所属の無い運営アカウントは PUBLIC")
    void preview_platformAccount_public() {
        given(accessControlService.isSystemAdmin(SYSADMIN)).willReturn(true);
        given(accessControlService.getRoleName(SYSADMIN, 2L, "TEAM")).willReturn(null);

        assertThat(resolver.resolveViewerRoleForPreview(SYSADMIN, "TEAM", 2L)).isEqualTo(ViewerRole.PUBLIC);
    }

    @Test
    @DisplayName("回帰: 一般利用者の ADMIN は ADMIN、所属なしは PUBLIC")
    void generalUser_regression() {
        given(accessControlService.isSystemAdmin(1L)).willReturn(false);
        given(accessControlService.getRoleName(1L, 2L, "TEAM")).willReturn("ADMIN");
        assertThat(resolver.resolveViewerRole(1L, "TEAM", 2L)).isEqualTo(ViewerRole.ADMIN);

        given(membershipBatchQueryService.snapshotForUser(1L, Set.of(TEAM), Set.of()))
                .willReturn(new UserScopeRoleSnapshot(false, Map.of(), Map.of(), Set.of(), Set.of()));
        given(accessControlService.isActiveUser(1L)).willReturn(true);
        assertThat(resolver.resolveViewerRolesFresh(1L, Set.of(TEAM)).get(TEAM)).isEqualTo(ViewerRole.PUBLIC);
    }
}
