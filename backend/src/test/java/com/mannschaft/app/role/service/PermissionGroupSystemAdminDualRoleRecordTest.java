package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.security.BillingPermissionGroupGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-A PR3 検分指摘 I2 の記録テスト: 運営アカウント（SYSTEM_ADMIN）がテナントに<b>兼任所属</b>している場合の
 * {@link PermissionGroupService#hasPermission} の扱いを、現状のまま固定して残す。
 *
 * <p>撤去前は兼任の SYSTEM_ADMIN の有効ロールが {@code "SYSTEM_ADMIN"}（権限グループの対象ロールと一致しない）で
 * fail-closed になっていたが、撤去後は有効ロールが<b>実ロール</b>（スコープの ADMIN / DEPUTY_ADMIN 等）になり、
 * 権限グループが他の利用者と同じように効く。判定側に「SYSTEM_ADMIN なら拒否」を新たに足さない原則（拒否型を増やさない）に従い、
 * 運営アカウントのテナント所属を禁止する側（#4939）で兼任そのものを起こさないことで担保する。</p>
 *
 * <p><b>前提（リリース前に必須）</b>: 運営アカウントの所属の点検 SQL（#4939）が 0 件であること。
 * 兼任が残っていると、そのアカウントに権限グループの権限が広がる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionGroupService 兼任の運営アカウントの扱い（Z-A PR3 I2 記録）")
class PermissionGroupSystemAdminDualRoleRecordTest {

    private static final Long SCOPE_ID = 1L;
    private static final Long GROUP_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long PERM_ID = 301L;
    private static final String PERM_NAME = "PROPERTY_HISTORY_MANAGE";

    @Mock
    private PermissionGroupRepository permissionGroupRepository;
    @Mock
    private PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private UserPermissionGroupRepository userPermissionGroupRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private RolePermissionCacheGenerationService cacheGenerationService;
    @Mock
    private UserRowLockService userRowLockService;
    @Mock
    private BillingPermissionGroupGuard billingPermissionGroupGuard;

    @InjectMocks
    private PermissionGroupService permissionGroupService;

    private void givenGroupAssignedWithPermission() {
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .id(GROUP_ID).teamId(SCOPE_ID).name("管理者グループ")
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .createdBy(200L).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        given(permissionGroupRepository.findByTeamId(SCOPE_ID)).willReturn(List.of(group));
        given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                UserPermissionGroupEntity.builder().userId(USER_ID).groupId(GROUP_ID).build()));
        given(permissionGroupPermissionRepository.findByGroupId(GROUP_ID)).willReturn(List.of(
                PermissionGroupPermissionEntity.builder().groupId(GROUP_ID).permissionId(PERM_ID).build()));
        PermissionEntity perm = org.mockito.Mockito.mock(PermissionEntity.class);
        given(perm.getName()).willReturn(PERM_NAME);
        given(permissionRepository.findById(PERM_ID)).willReturn(Optional.of(perm));
    }

    @Test
    @DisplayName("兼任の運営アカウント: 有効ロールは実ロールで、権限グループは他の利用者と同じく効く（現状の記録。兼任は #4939 で禁止済みが前提）")
    void dualRoleSystemAdminIsJudgedByEffectiveScopeRole() {
        given(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).willReturn(true);
        given(accessControlService.resolveEffectiveRoleName(USER_ID, SCOPE_ID, "TEAM")).willReturn("DEPUTY_ADMIN");
        givenGroupAssignedWithPermission();

        assertThat(permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, PERM_NAME)).isTrue();
    }

    @Test
    @DisplayName("運営アカウント単独（所属なし）: 有効ロールが無く権限グループは効かない")
    void systemAdminAloneHasNoEffectiveRoleSoNoPermission() {
        given(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);

        assertThat(permissionGroupService.hasPermission(USER_ID, "TEAM", SCOPE_ID, PERM_NAME)).isFalse();
    }
}
