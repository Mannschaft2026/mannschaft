package com.mannschaft.app.shiftbudget.architecture;

import com.mannschaft.app.common.architecture.ArchUnitTestTag;
import com.mannschaft.app.common.architecture.ProductionClasses;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseGate;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: 月次締めの入口は {@link MonthlyShiftBudgetCloseGate} に限る（CMP-261021-0657・凍結なし）。
 *
 * <p>「月末を過ぎていない月（当月・未来月）は締めない」判定は Gate が壁時計（JST）で行う。
 * {@link MonthlyShiftBudgetCloseService} のコンストラクタ署名は ArchUnit 凍結ストアのキーに含まれ、
 * 壁時計を注入できないため、Service 内部の共通経路で判定する多重防御の代わりに、
 * 締め処理（{@code close} / {@code closeAll} / {@code closeFromBatch}）を Gate 以外の本番クラスから
 * 直接呼ぶことを本番人で禁止する（新しい入口が判定を素通りするのを防ぐ）。</p>
 */
@DisplayName("月次締めの入口は MonthlyShiftBudgetCloseGate に限る番人（CMP-261021-0657・凍結なし）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class MonthlyShiftBudgetCloseGateGuardArchTest {

    private static final String SERVICE = MonthlyShiftBudgetCloseService.class.getName();
    private static final String GATE = MonthlyShiftBudgetCloseGate.class.getName();
    private static final Set<String> CLOSE_METHODS = Set.of("close", "closeAll", "closeFromBatch");

    @Test
    @DisplayName("Gate 以外の本番クラスは MonthlyShiftBudgetCloseService の締め処理を直接呼ばない")
    void 締め処理はGate経由でのみ呼ばれる() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : ProductionClasses.get()) {
            String origin = clazz.getName();
            if (origin.equals(SERVICE) || origin.equals(GATE)) {
                continue;
            }
            closeAccesses(clazz).forEach(access -> violations.add(access.getDescription()));
        }

        assertThat(violations)
                .as("当月・未来月の判定を素通りする入口がある。MonthlyShiftBudgetCloseGate を経由すること")
                .isEmpty();
    }

    @Test
    @DisplayName("Gate は締め処理を実際に呼んでいる（空虚な緑の防止）")
    void Gateは締め処理を呼ぶ() {
        JavaClass gate = ProductionClasses.get().get(GATE);

        assertThat(closeAccesses(gate).map(a -> a.getTarget().getName()))
                .contains("close", "closeAll");
    }

    private static Stream<JavaAccess<?>> closeAccesses(JavaClass clazz) {
        Stream<JavaAccess<?>> calls = clazz.getMethodCallsFromSelf().stream().map(a -> (JavaAccess<?>) a);
        Stream<JavaAccess<?>> references =
                clazz.getMethodReferencesFromSelf().stream().map(a -> (JavaAccess<?>) a);
        return Stream.concat(calls, references)
                .filter(access -> access.getTargetOwner().getName().equals(SERVICE))
                .filter(access -> CLOSE_METHODS.contains(access.getTarget().getName()));
    }
}
