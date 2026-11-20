package com.mannschaft.app.shift.architecture;

import com.mannschaft.app.common.architecture.ArchUnitTestTag;
import com.mannschaft.app.common.architecture.ProductionClasses;
import com.mannschaft.app.shift.service.ShiftPublicationBudgetContext;
import com.mannschaft.app.shift.service.ShiftPublicationHourlyRateVerifier;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * CMP-261115-1821 G12: 公開 TX の本体と時給検証が、予算・機能判定のドメインへ到達しないことを固定する。
 *
 * <p>公開の予算連動判定は TX の外（{@code ShiftScheduleFacade}・認可の後）で値に固定し、TX の中は
 * shift ドメインの Repository だけで時給を照合する（設計書「Root採用・軍議検分反映」）。是正前は公開 TX から
 * shiftbudget の Service が allocation・feature の Repository を直接引いており、D-3T の新規越境になっていた。
 * 横断の番人（D-3/D-3T/D-5・AuthzTxFacadeRegistry）に加え、本件の境界を名指しで固定する。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("CMP-261115-1821 公開 TX は shiftbudget・budget へ到達しない")
class ShiftPublicationTxBoundaryArchTest {

    @Test
    void 公開TX本体と時給検証と予算contextはshiftbudgetとbudgetに依存しない() {
        noClasses()
                .that().haveFullyQualifiedName(ShiftScheduleService.class.getName())
                .or().haveFullyQualifiedName(ShiftPublicationHourlyRateVerifier.class.getName())
                .or().haveFullyQualifiedName(ShiftPublicationBudgetContext.class.getName())
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.mannschaft.app.shiftbudget..", "com.mannschaft.app.budget..")
                .check(ProductionClasses.get());
    }
}
