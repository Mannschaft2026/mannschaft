package com.mannschaft.app.search;

import com.mannschaft.app.common.architecture.ProductionClasses;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-C17: search ドメインは集約のみを担い、他ドメインの Repository を直接 DI しない
 * （設計書 §1「アーキテクチャ（SearchContributor 契約）」）。旧 GlobalSearchService は撤去される。
 *
 * <p>凍結ストア（archunit_store）を使わない素の ArchUnit 規則。本番クラスは共有ホルダ
 * {@link ProductionClasses} から得て（CMP-261002-1606）、規則の対象を search 配下に絞り、
 * 既存違反の凍結に頼らず「ゼロ」を固定する（全体の D-5 番人 {@code CrossDomainRepositoryDependencyArchTest}
 * の凍結分は旧実装の撤去で減る）。</p>
 */
@DisplayName("search ドメイン境界（AC-C17 試練）")
class SearchDomainBoundaryTest {

    /** 本番クラス全体（JVM 内で 1 回だけ取り込む共有ホルダ。規則側で search 配下に絞る）。 */
    private final JavaClasses productionClasses = ProductionClasses.get();

    @Test
    @DisplayName("AC-C17: search 配下のクラスは他ドメインの repository パッケージに依存しない")
    void AC_C17_他ドメインRepositoryに依存しない() {
        noClasses().that().resideInAPackage("com.mannschaft.app.search..")
                .should().dependOnClassesThat(resideInAPackage("com.mannschaft.app..repository..")
                        .and(not(resideInAPackage("com.mannschaft.app.search.."))))
                .because("search は SearchContributor 契約の集約のみを担い、各種別の検索は各ドメインが実装する")
                .check(productionClasses);
    }

    @Test
    @DisplayName("AC-C17: 旧 GlobalSearchService（業務データ9種別の横断検索）は撤去されている")
    void AC_C17_旧サービスは撤去() {
        assertThatThrownBy(() -> Class.forName("com.mannschaft.app.search.service.GlobalSearchService"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
