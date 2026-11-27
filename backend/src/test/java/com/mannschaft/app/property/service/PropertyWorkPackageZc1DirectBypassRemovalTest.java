package com.mannschaft.app.property.service;

import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.controller.PropertyWorkPackageController;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService.MaskedView;
import com.mannschaft.app.role.service.PermissionGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: 物件の工事パッケージの SYSTEM_ADMIN 直書き素通し
 * （{@link PropertyWorkPackageMaskingService#isVisible}・金額閲覧・{@code PropertyWorkPackageController#canEdit}）を
 * 除去する受け入れテスト。
 *
 * <p>分類表の A 行（MaskingService.isVisible:131・canViewAmount:171・Controller.canEdit:444）に対応する。
 * 戦役 Z-A PR4 の反転一覧で「PR4/ZC（直書き snapshot.isSystemAdmin が残る）」とされた 2 行の後継。
 * 運営アカウント（{@code snapshot.systemAdmin()=true}・所属なし）は、所属の無い利用者（{@code systemAdmin=false}・所属なし）と
 * <b>同じ結果</b>（不可視・金額マスク・編集不可）になる。{@code systemAdmin} 項目の値そのものは Z-A の方針どおり正しく計算し続ける
 * （本テストは値を true にして渡す）。</p>
 */
@DisplayName("Z-C1: 物件工事パッケージの SYSTEM_ADMIN 直書き素通しの除去")
class PropertyWorkPackageZc1DirectBypassRemovalTest {

    private PermissionGroupService permissionGroupService;
    private PropertyWorkPackageMaskingService service;

    private static final ScopeKey SCOPE = new ScopeKey("TEAM", 100L);

    /** 所属の無い運営アカウント。 */
    private static final UserScopeRoleSnapshot PLATFORM =
            new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of());
    /** 所属の無い一般利用者（比較の基準）。 */
    private static final UserScopeRoleSnapshot OUTSIDER =
            new UserScopeRoleSnapshot(false, Map.of(), Map.of(), Set.of(), Set.of());
    private static final UserScopeRoleSnapshot ADMIN =
            new UserScopeRoleSnapshot(false, Map.of(SCOPE, "ADMIN"), Map.of(), Set.of(), Set.of());

    @BeforeEach
    void setUp() {
        permissionGroupService = mock(PermissionGroupService.class);
        service = new PropertyWorkPackageMaskingService(permissionGroupService);
    }

    private static PropertyWorkPackageEntity packageOf(WorkPackageVisibility v) {
        PropertyWorkPackageEntity e = PropertyWorkPackageEntity.builder()
                .scopeType("TEAM").scopeId(100L).workType(WorkType.RENOVATION).title("外壁塗装")
                .estimatedAmount(12_000_000L).contractAmount(11_500_000L).actualAmount(11_400_000L)
                .currency("JPY").visibility(v).status(WorkPackageStatus.IN_PROGRESS)
                .attachmentCount(0).commentCount(0).isDisclosable(true).createdBy(7L)
                .build();
        ReflectionTestUtils.setField(e, "id", 1L);
        return e;
    }

    @Test
    @DisplayName("A(isVisible): どの公開範囲でも、運営アカウントの可視判定は所属の無い利用者と同じ（不可視）")
    void isVisible_platformSameAsOutsider() {
        for (WorkPackageVisibility v : WorkPackageVisibility.values()) {
            PropertyWorkPackageEntity pkg = packageOf(v);
            assertThat(service.isVisible(pkg, PLATFORM))
                    .as("visibility=%s: 運営アカウントがテナントの工事パッケージを見られてはならない", v)
                    .isEqualTo(service.isVisible(pkg, OUTSIDER))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A(applyMasking / canViewAmount): 運営アカウントに金額・業者情報を出さない（hidden）")
    void applyMasking_platformIsHidden() {
        for (WorkPackageVisibility v : WorkPackageVisibility.values()) {
            MaskedView m = service.applyMasking(packageOf(v), null, 900L, PLATFORM);
            assertThat(m.visible()).as("visibility=%s", v).isFalse();
            assertThat(m.canViewAmount()).as("visibility=%s", v).isFalse();
        }
    }

    @Test
    @DisplayName("A(Controller.canEdit): 所属の無い運営アカウントは編集・削除可能フラグが立たない")
    void controllerCanEdit_platformIsFalse() throws Exception {
        PropertyWorkPackageController controller = Mockito.mock(PropertyWorkPackageController.class);
        Method canEdit = PropertyWorkPackageController.class.getDeclaredMethod(
                "canEdit", PropertyWorkPackageEntity.class, UserScopeRoleSnapshot.class);
        canEdit.setAccessible(true);
        PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_ONLY);

        assertThat((Boolean) canEdit.invoke(controller, pkg, OUTSIDER)).isFalse();
        assertThat((Boolean) canEdit.invoke(controller, pkg, PLATFORM))
                .as("運営アカウントに編集・削除の導線を出してはならない")
                .isFalse();
        assertThat((Boolean) canEdit.invoke(controller, pkg, ADMIN)).as("回帰: スコープ ADMIN は編集可").isTrue();
    }

    @Test
    @DisplayName("回帰: スコープ ADMIN は全公開範囲で可視・金額表示")
    void regression_admin() {
        for (WorkPackageVisibility v : WorkPackageVisibility.values()) {
            MaskedView m = service.applyMasking(packageOf(v), null, 7L, ADMIN);
            assertThat(m.visible()).as("visibility=%s", v).isTrue();
            assertThat(m.canViewAmount()).as("visibility=%s", v).isTrue();
        }
    }
}
