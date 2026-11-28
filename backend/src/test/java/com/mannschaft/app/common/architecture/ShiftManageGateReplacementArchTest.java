package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.service.ShiftManageAccessGate;
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
 * <p>{@code com.mannschaft.app.shift..} のクラスは、DEPUTY_ADMIN を一律に管理者とみなす、または SYSTEM_ADMIN を
 * 特別扱いする次の 6 つの呼び出しをしてはならない。シフトの管理操作・閲覧の判定は {@code ShiftManageAccessGate}（唯一の例外。内部で
 * {@code hasAdminOrPermissionInScope} と所属判定のためにだけ {@code isAdminOrAbove} を使う）を通す。</p>
 * <ul>
 *   <li>{@code ScopeConcealingAccessGate#requireAdminOrConceal}</li>
 *   <li>{@code ScopeConcealingAccessGate#requireOwnerOrAdminOrConceal}</li>
 *   <li>{@code AccessControlService#isAdminOrAbove}</li>
 *   <li>{@code AccessControlService#checkAdminOrAbove}</li>
 *   <li>{@code AccessControlService#isSystemAdmin}（SYSTEM_ADMIN の素通し。マスター裁定 2026-11-25「今回拒否する」）</li>
 *   <li>{@code ScopeConcealingAccessGate#requireMemberOrConceal}（SYSTEM_ADMIN を素通しする共通ゲート。shift では
 *       {@code ShiftManageAccessGate#requireMemberOrConceal} を使う。共通ゲート自体は他ドメインに影響するので変えない）</li>
 * </ul>
 *
 * <p>許可表（SYSTEM_ADMIN の短絡を残す呼び出し元）は現在<b>空</b>。可用性（{@code ShiftAvailabilityService}）も
 * 本人の登録のためだけに SYSTEM_ADMIN を通していたが、チームロールの無い SYSTEM_ADMIN を部外者として扱うよう揃えた。
 * 残す必要が生じたら {@link #ALLOWED_CALLERS} に「呼び出し元クラス#メソッド」と理由を書くこと。</p>
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
            ACCESS_CONTROL + "#checkAdminOrAbove",
            ACCESS_CONTROL + "#isSystemAdmin",
            SCOPE_GATE + "#requireMemberOrConceal");

    /** 禁止呼び出しの許可表（呼び出し元クラス FQN → 理由）。現在は空。残す呼び出しは理由を必ず書く。 */
    static final java.util.Map<String, String> ALLOWED_CALLERS = java.util.Map.of();

    @Test
    @DisplayName("shift パッケージは DEPUTY_ADMIN 一律許可・SYSTEM_ADMIN 素通しの 6 呼び出しをしない（ShiftManageAccessGate と許可表を除く）")
    void shiftDoesNotCallBlanketAdminGates() {
        Set<String> exempt = new java.util.HashSet<>(ALLOWED_CALLERS.keySet());
        exempt.add(SHIFT_GATE);
        assertThat(violations(ProductionClasses.get(), SHIFT_PACKAGE_PREFIX, exempt)).isEmpty();
    }

    @Test
    @DisplayName("許可表の各エントリは理由が書かれ、実在するクラスを指す")
    void allowedCallersHaveReasonAndExist() {
        ALLOWED_CALLERS.forEach((caller, reason) -> {
            assertThat(reason).as("理由: " + caller).isNotBlank();
            assertThat(ProductionClasses.get().contain(caller)).as("実在: " + caller).isTrue();
        });
    }

    @Test
    @DisplayName("例外の ShiftManageAccessGate が実在する（リネームで例外が空振りしない）")
    void exemptionTargetExists() {
        assertThat(ProductionClasses.get().contain(SHIFT_GATE)).isTrue();
    }

    @Test
    @DisplayName("陽性対照: 6 つの禁止呼び出しを持つ合成クラスはちょうど 6 件検出される")
    void positiveControlDetectsAllForbiddenCalls() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ViolatingFixture.class);
        List<String> found = violations(fixture, ViolatingFixture.class.getPackageName(), Set.of());
        assertThat(found).hasSize(FORBIDDEN.size()).hasSize(6);
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
        // 許可された呼び出し（isMember・hasAdminOrPermissionInScope・shift 用ゲートの requireMemberOrConceal）は検出しない。
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
            access.isSystemAdmin(1L);
            gate.requireMemberOrConceal(1L, 2L, "TEAM", CommonErrorCode.COMMON_002, true);
        }
    }

    @SuppressWarnings("unused")
    static class CleanFixture {
        boolean allowed(ShiftManageAccessGate shiftGate, AccessControlService access) {
            shiftGate.requireMemberOrConceal(1L, 2L, CommonErrorCode.COMMON_002, true);
            return access.isMember(1L, 2L, "TEAM")
                    || access.hasAdminOrPermissionInScope(1L, 2L, "TEAM", "MANAGE_SHIFTS");
        }
    }
}
