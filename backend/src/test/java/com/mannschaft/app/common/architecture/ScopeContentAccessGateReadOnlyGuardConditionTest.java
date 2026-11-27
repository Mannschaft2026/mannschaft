package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.ScopeContentAccessGate;
import com.mannschaft.app.common.gatereadonly.CleanGate;
import com.mannschaft.app.common.gatereadonly.ViolatingGates;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 門の読み取り専用番人（{@link ScopeContentAccessGateReadOnlyArchTest}）の自己検査。違反するダミーの門で赤になる。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class ScopeContentAccessGateReadOnlyGuardConditionTest {

    private final JavaClasses fixtureClasses = new ClassFileImporter()
        .importPackages("com.mannschaft.app.common.gatereadonly");

    @Test
    void 番人の対象は本物の門の完全修飾名である() {
        assertThat(ScopeContentAccessGateReadOnlyArchTest.GATE_CLASS_NAME)
            .isEqualTo(ScopeContentAccessGate.class.getName());
        assertThat(CrossDomainTransactionalTransitiveArchTest.AUTHORIZATION_BOUNDARIES)
            .containsExactly(ScopeContentAccessGateReadOnlyArchTest.GATE_CLASS_NAME);
    }

    @Test
    void 本物の門は読み取り専用である() {
        JavaClasses production = ProductionClasses.get();
        assertThat(ScopeContentAccessGateReadOnlyArchTest.findViolations(
            production.get(ScopeContentAccessGate.class), production)).isEmpty();
    }

    @Test
    void 読み取りだけの門は合格する() {
        assertThat(detect(CleanGate.class)).isEmpty();
    }

    @Test
    void readOnlyでないTransactionalはクラスでもメソッドでも赤になる() {
        assertThat(detect(ViolatingGates.WriteTransactionalClassGate.class))
            .anyMatch(v -> v.contains("readOnly = true でない"));
        assertThat(detect(ViolatingGates.WriteTransactionalMethodGate.class))
            .anyMatch(v -> v.contains("readOnly = true でない"));
    }

    @Test
    void 中継を経由したRepositoryのsaveは赤になる() {
        assertThat(detect(ViolatingGates.SaveThroughHelperGate.class))
            .anyMatch(v -> v.contains("GateFixtureRepository.save()") && v.contains("WriteHelper.write()"));
    }

    @Test
    void interfaceの実装を経由したdeleteは赤になる() {
        assertThat(detect(ViolatingGates.DeleteThroughPortGate.class))
            .anyMatch(v -> v.contains("GateFixtureRepository.deleteById()"));
    }

    @Test
    void Modifyingの付いたメソッドは赤になる() {
        assertThat(detect(ViolatingGates.ModifyingGate.class))
            .anyMatch(v -> v.contains("GateFixtureRepository.bulkTouch()"));
    }

    @Test
    void 悲観ロックは赤になる() {
        assertThat(detect(ViolatingGates.LockAnnotationGate.class)).anyMatch(v -> v.contains("@Lock"));
        assertThat(detect(ViolatingGates.ForUpdateQueryGate.class)).anyMatch(v -> v.contains("FOR UPDATE"));
        assertThat(detect(ViolatingGates.EntityManagerLockGate.class)).anyMatch(v -> v.contains("PESSIMISTIC_WRITE"));
    }

    @Test
    void EntityManagerの書き込みは赤になる() {
        assertThat(detect(ViolatingGates.EntityManagerWriteGate.class))
            .anyMatch(v -> v.contains("EntityManager.persist()"));
    }

    private List<String> detect(Class<?> gate) {
        return ScopeContentAccessGateReadOnlyArchTest.findViolations(fixtureClasses.get(gate), fixtureClasses);
    }
}
