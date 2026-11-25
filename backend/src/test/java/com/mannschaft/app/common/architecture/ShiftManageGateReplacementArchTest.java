package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261125-2114: シフト管理の判定を「ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけ」へ置き換えた後の<b>置換漏れ防止</b>。
 *
 * <p>{@code com.mannschaft.app.shift..} のクラスは、DEPUTY_ADMIN を一律に管理者とみなす次の 4 つの呼び出しを
 * してはならない。シフトの管理操作・閲覧の判定は {@code ShiftManageAccessGate}（唯一の例外。内部で
 * {@code hasAdminOrPermissionInScope} と所属判定のためにだけ {@code isAdminOrAbove} を使う）を通す。</p>
 * <ul>
 *   <li>{@code ScopeConcealingAccessGate#requireAdminOrConceal}</li>
 *   <li>{@code ScopeConcealingAccessGate#requireOwnerOrAdminOrConceal}</li>
 *   <li>{@code AccessControlService#isAdminOrAbove}</li>
 *   <li>{@code AccessControlService#checkAdminOrAbove}</li>
 * </ul>
 *
 * <p>shiftbudget（{@code com.mannschaft.app.shiftbudget..}）は別ドメインなので巻き込まない（パッケージ境界は
 * {@code .shift.} で切る）。凍結ストアは使わない（違反は 0 件が正）。検出力は合成クラスの陽性・陰性対照で示す。</p>
 */
@DisplayName("CMP-261125-2114 シフトの MANAGE_SHIFTS 置換漏れ防止")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ShiftManageGateReplacementArchTest {

    private static final String SHIFT_PACKAGE_PREFIX = "com.mannschaft.app.shift.";
    private static final String SHIFT_GATE = "com.mannschaft.app.shift.service.ShiftManageAccessGate";
    private static final String SCOPE_GATE = ScopeConcealingAccessGate.class.getName();
    private static final String ACCESS_CONTROL = AccessControlService.class.getName();

    /** 禁止する呼び出し: 「所有者 FQN#メソッド名」。 */
    private static final Set<String> FORBIDDEN = Set.of(
            SCOPE_GATE + "#requireAdminOrConceal",
            SCOPE_GATE + "#requireOwnerOrAdminOrConceal",
            ACCESS_CONTROL + "#isAdminOrAbove",
            ACCESS_CONTROL + "#checkAdminOrAbove");

    @Test
    @DisplayName("shift パッケージは DEPUTY_ADMIN 一律許可の 4 呼び出しをしない（ShiftManageAccessGate を除く）")
    void shiftDoesNotCallBlanketAdminGates() {
        assertThat(violations(ProductionClasses.get(), SHIFT_PACKAGE_PREFIX, Set.of(SHIFT_GATE))).isEmpty();
    }

    @Test
    @DisplayName("例外の ShiftManageAccessGate が実在する（リネームで例外が空振りしない）")
    void exemptionTargetExists() {
        assertThat(ProductionClasses.get().contain(SHIFT_GATE)).isTrue();
    }

    @Test
    @DisplayName("陽性対照: 4 つの禁止呼び出しを持つ合成クラスはちょうど 4 件検出される")
    void positiveControlDetectsAllFourCalls() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ViolatingFixture.class);
        List<String> found = violations(fixture, ViolatingFixture.class.getPackageName(), Set.of());
        assertThat(found).hasSize(4);
        for (String forbidden : FORBIDDEN) {
            String method = forbidden.substring(forbidden.indexOf('#') + 1);
            assertThat(found).as(method).anyMatch(v -> v.contains("#" + method));
        }
    }

    @Test
    @DisplayName("陰性対照: 例外クラスの指定・許可された呼び出し・対象外パッケージでは検出しない")
    void negativeControlsAreNotFlagged() {
        JavaClasses violating = new ClassFileImporter().importClasses(ViolatingFixture.class);
        // 例外クラスとして指定すれば検出されない。
        assertThat(violations(violating, ViolatingFixture.class.getPackageName(),
                Set.of(ViolatingFixture.class.getName()))).isEmpty();
        // 別ドメイン（パッケージ境界の外）は巻き込まない。
        assertThat(violations(violating, "com.mannschaft.app.shiftbudget.", Set.of())).isEmpty();
        // 許可された呼び出し（isMember・hasAdminOrPermissionInScope）は検出しない。
        JavaClasses clean = new ClassFileImporter().importClasses(CleanFixture.class);
        assertThat(violations(clean, CleanFixture.class.getPackageName(), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("パッケージ境界は shift. と shiftbudget. を取り違えない")
    void packagePrefixDoesNotSwallowShiftBudget() {
        assertThat("com.mannschaft.app.shiftbudget.service.X".startsWith(SHIFT_PACKAGE_PREFIX)).isFalse();
        assertThat("com.mannschaft.app.shift.service.X".startsWith(SHIFT_PACKAGE_PREFIX)).isTrue();
    }

    static List<String> violations(JavaClasses classes, String packagePrefix, Set<String> exemptClasses) {
        return classes.stream()
                .filter(c -> (c.getPackageName() + ".").startsWith(packagePrefix.endsWith(".")
                        ? packagePrefix : packagePrefix + "."))
                .filter(c -> !exemptClasses.contains(c.getName()))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(ShiftManageGateReplacementArchTest::isForbidden)
                .map(call -> call.getOriginOwner().getName() + "#" + call.getOrigin().getName()
                        + " -> " + call.getTargetOwner().getName() + "#" + call.getTarget().getName())
                .sorted()
                .collect(Collectors.toList());
    }

    private static boolean isForbidden(JavaMethodCall call) {
        return FORBIDDEN.contains(call.getTargetOwner().getName() + "#" + call.getTarget().getName());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 合成クラス（実行はされない。呼び出し先の参照だけが検査対象）
    // ═════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unused")
    static class ViolatingFixture {
        void all(ScopeConcealingAccessGate gate, AccessControlService access) {
            gate.requireAdminOrConceal(1L, 2L, "TEAM", CommonErrorCode.COMMON_002);
            gate.requireOwnerOrAdminOrConceal(1L, 2L, "TEAM", 3L, CommonErrorCode.COMMON_002);
            access.isAdminOrAbove(1L, 2L, "TEAM");
            access.checkAdminOrAbove(1L, 2L, "TEAM");
        }
    }

    @SuppressWarnings("unused")
    static class CleanFixture {
        boolean allowed(ScopeConcealingAccessGate gate, AccessControlService access) {
            gate.requireMemberOrConceal(1L, 2L, "TEAM", CommonErrorCode.COMMON_002, true);
            return access.isMember(1L, 2L, "TEAM")
                    || access.hasAdminOrPermissionInScope(1L, 2L, "TEAM", "MANAGE_SHIFTS");
        }
    }
}
