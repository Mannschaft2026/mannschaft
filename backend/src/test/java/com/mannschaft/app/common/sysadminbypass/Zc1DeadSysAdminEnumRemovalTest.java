package com.mannschaft.app.common.sysadminbypass;

import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.publicview.visibility.ViewerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: 生成元が無くなった SYSTEM_ADMIN の列挙値と、それを前提にした枝の除去。
 *
 * <ul>
 *   <li>{@link ViewerRole#SYSTEM_ADMIN}: dashboard の RoleResolver は戦役 Z-A PR4 で SYSTEM_ADMIN を返さなくなった
 *       （z-a-design.md §1-7「列挙値は Z-C で削除する」）。{@code isAdminOrAbove} の枝・{@code fromString("SYSTEM_ADMIN")}・
 *       DashboardService.filterAnnouncementGated と OrgAnnouncementAudienceAccess の比較は到達しない枝。</li>
 *   <li>{@link ViewerStatus#SYSTEM_ADMIN}: publicview の ViewerContextBuilder は Z-A PR4 で SYSTEM_ADMIN を作らなくなった。
 *       IdentityVisibilityResolver の {@code case SYSTEM_ADMIN -> 本名} は到達しない許可型の枝。</li>
 * </ul>
 *
 * <p>名前で検査するので、列挙値を削除してもこのテスト自体はコンパイルできる。現状は列挙値が残るので赤。</p>
 */
@DisplayName("Z-C1: 生成元の無い SYSTEM_ADMIN 列挙値の除去（ViewerRole・ViewerStatus）")
class Zc1DeadSysAdminEnumRemovalTest {

    @Test
    @DisplayName("ViewerRole に SYSTEM_ADMIN の列挙値が無い（ダッシュボードの閲覧者ロールは実ロールだけ）")
    void viewerRoleHasNoSystemAdmin() {
        assertThat(Arrays.stream(ViewerRole.values()).map(Enum::name))
                .doesNotContain("SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("ViewerRole.fromString(\"SYSTEM_ADMIN\") は未知の値として拒否される")
    void viewerRoleFromStringRejectsSystemAdmin() {
        assertThatThrownBy(() -> ViewerRole.fromString("SYSTEM_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ViewerRole.isAdminOrAbove は ADMIN・DEPUTY_ADMIN だけ true（列挙値の削除後。SYSTEM_ADMIN の枝を残さない）")
    void viewerRoleAdminOrAboveRegression() {
        for (ViewerRole r : ViewerRole.values()) {
            boolean expected = r.name().equals("ADMIN") || r.name().equals("DEPUTY_ADMIN");
            assertThat(r.isAdminOrAbove()).as(r.name()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("ViewerStatus に SYSTEM_ADMIN の列挙値が無い（公開ページの本名開示に運営の枝を残さない）")
    void viewerStatusHasNoSystemAdmin() {
        assertThat(Arrays.stream(ViewerStatus.values()).map(Enum::name))
                .doesNotContain("SYSTEM_ADMIN");
    }
}
