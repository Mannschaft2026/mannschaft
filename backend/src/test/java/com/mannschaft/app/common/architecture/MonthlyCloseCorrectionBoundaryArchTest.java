package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 試練15・23（AC15 / AC15改）: 締め後訂正の TX 境界の番人。
 *
 * <p>確定設計の規則: 他ドメインを呼ぶのは {@code @Transactional} を一切付けない段取り役だけ。各ドメインの TX は
 * 自ドメイン Repository のみに到達する。受け渡しは値（record DTO）。よって</p>
 * <ol>
 *   <li>段取り役（Coordinator / Facade / AccessPolicy）はクラスにもメソッドにも {@code @Transactional} を持たない</li>
 *   <li>各ドメインの訂正 TX サービスは {@code @Transactional} を持ち、D-3T（推移的越境）違反が0</li>
 *   <li>締め後訂正で新設するクラスは D-3T・D-5（直接の他ドメイン Repository 依存）違反が0で、
 *       凍結ストアにも新しいキーを持たない</li>
 *   <li>段取り役は shiftbudget の snapshot TX と budget の command TX へ実際に委譲する（骨抜き防止）</li>
 * </ol>
 * <p>試練段階では TX サービスが未作成・段取り役が委譲していないため、存在と委譲の検査で red になる。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("CMP-260909-2152 締め後訂正の TX 境界（D-3T/D-5 新凍結キー0・段取り役に @Transactional なし）")
class MonthlyCloseCorrectionBoundaryArchTest {

    private static final String SB = "com.mannschaft.app.shiftbudget.";
    private static final String BG = "com.mannschaft.app.budget.";

    /** @Transactional を付けない段取り役。 */
    private static final List<String> ORCHESTRATORS = List.of(
            SB + "service.MonthlyShiftBudgetCorrectionCoordinator",
            SB + "service.MonthlyShiftBudgetCorrectionHistoryFacade",
            SB + "service.ShiftBudgetAllocationReadFacade",
            SB + "service.ShiftBudgetScopeAccessPolicy",
            SB + "service.ShiftBudgetSummaryFacade");

    /** 自ドメインに閉じた TX サービス（クラス名 → ドメイン）。 */
    private static final Map<String, String> DOMAIN_TX_SERVICES = Map.of(
            SB + "service.ShiftBudgetCorrectionSnapshotService", "shiftbudget",
            BG + "service.ShiftBudgetCorrectionCommandService", "budget",
            BG + "service.ShiftBudgetCorrectionQueryService", "budget");

    /** 締め後訂正で新設・改修するクラスの名前（凍結キー検査の対象）。 */
    private static final Pattern CORRECTION_CLASS = Pattern.compile(
            "^com\\.mannschaft\\.app\\.(shiftbudget|budget)\\..*"
                    + "(Correction|UncountedShift|AllocationReadFacade|ScopeAccessPolicy|MonthlyCloseEvent|MonthlyCloseRecord).*");

    private final JavaClasses classes = ProductionClasses.get();

    @Test
    @DisplayName("試練23 段取り役はクラスにもメソッドにも @Transactional を持たない")
    void 試練23_段取り役にTransactionalが無い() {
        for (String name : ORCHESTRATORS) {
            assertThat(classes.contain(name)).as("段取り役 %s が存在する", name).isTrue();
            JavaClass orchestrator = classes.get(name);
            assertThat(isTransactional(orchestrator.getAnnotations().stream().map(a -> a.getRawType().getName())))
                    .as("%s のクラス注釈", name).isFalse();
            for (JavaMethod method : orchestrator.getMethods()) {
                assertThat(isTransactional(method.getAnnotations().stream().map(a -> a.getRawType().getName())))
                        .as("%s#%s の注釈", name, method.getName()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("試練15 各ドメインの訂正 TX サービスは @Transactional を持ち、推移的にも自ドメインの Repository だけに到達する")
    void 試練15_訂正TXサービスは自ドメインに閉じる() {
        for (Map.Entry<String, String> e : DOMAIN_TX_SERVICES.entrySet()) {
            assertThat(classes.contain(e.getKey())).as("TX サービス %s が存在する", e.getKey()).isTrue();
            JavaClass service = classes.get(e.getKey());
            assertThat(DomainPackages.domainOf(service.getPackageName())).isEqualTo(e.getValue());
            boolean transactional = isTransactional(service.getAnnotations().stream().map(a -> a.getRawType().getName()))
                    || service.getMethods().stream().anyMatch(m ->
                    isTransactional(m.getAnnotations().stream().map(a -> a.getRawType().getName())));
            assertThat(transactional).as("%s は自ドメインの TX 入口である", e.getKey()).isTrue();
            assertThat(CrossDomainTransactionalTransitiveArchTest.findViolations(service, classes))
                    .as("%s の D-3T 違反", e.getKey()).isEmpty();
        }
    }

    @Test
    @DisplayName("試練15 段取り役は shiftbudget の snapshot TX と budget の command / query TX へ委譲する")
    void 試練15_段取り役は両ドメインのTXへ委譲する() {
        assertThat(directTargets(SB + "service.MonthlyShiftBudgetCorrectionCoordinator"))
                .contains(SB + "service.ShiftBudgetCorrectionSnapshotService",
                        BG + "service.ShiftBudgetCorrectionCommandService");
        assertThat(directTargets(SB + "service.MonthlyShiftBudgetCorrectionHistoryFacade"))
                .contains(BG + "service.ShiftBudgetCorrectionQueryService");
        assertThat(directTargets(SB + "service.ShiftBudgetAllocationReadFacade"))
                .contains(BG + "service.ShiftBudgetCorrectionQueryService");
    }

    @Test
    @DisplayName("試練15 締め後訂正の新設クラスは D-3T・D-5 違反0、凍結ストアにも新キーが無い")
    void 試練15_新凍結キーが0() throws IOException {
        List<JavaClass> targets = classes.stream()
                .filter(c -> CORRECTION_CLASS.matcher(c.getName()).matches())
                .filter(c -> !c.getName().contains("$"))
                .toList();
        assertThat(targets.stream().map(JavaClass::getName).collect(Collectors.toSet()))
                .as("訂正の段取り役・TX サービスが揃っている")
                .containsAll(Set.of(SB + "service.MonthlyShiftBudgetCorrectionCoordinator",
                        SB + "service.ShiftBudgetCorrectionSnapshotService",
                        BG + "service.ShiftBudgetCorrectionCommandService",
                        BG + "service.ShiftBudgetCorrectionQueryService"));

        List<String> violations = new ArrayList<>();
        for (JavaClass c : targets) {
            CrossDomainTransactionalTransitiveArchTest.findViolations(c, classes)
                    .forEach(v -> violations.add(v.freezeKey()));
            String source = DomainPackages.domainOf(c.getPackageName());
            for (Dependency dep : c.getDirectDependenciesFromSelf()) {
                String pkg = dep.getTargetClass().getPackageName();
                String target = DomainPackages.domainOf(pkg);
                if ((pkg.endsWith(".repository") || pkg.contains(".repository.")) && target != null
                        && !target.equals(source) && !DomainPackages.isSharedDomain(target)) {
                    violations.add("[D-5] " + c.getName() + " -> " + dep.getTargetClass().getName());
                }
            }
        }
        assertThat(violations).as("締め後訂正で新しい越境を作らない").isEmpty();

        List<String> frozen = frozenLinesMentioning(targets.stream().map(JavaClass::getName).toList());
        assertThat(frozen).as("凍結ストアに締め後訂正のキーを足さない").isEmpty();
    }

    private Set<String> directTargets(String name) {
        assertThat(classes.contain(name)).as("%s が存在する", name).isTrue();
        return classes.get(name).getDirectDependenciesFromSelf().stream()
                .map(d -> d.getTargetClass().getName())
                .collect(Collectors.toSet());
    }

    private static boolean isTransactional(Stream<String> annotationNames) {
        return annotationNames.anyMatch(n -> n.equals("org.springframework.transaction.annotation.Transactional")
                || n.equals("jakarta.transaction.Transactional"));
    }

    private static List<String> frozenLinesMentioning(List<String> classNames) throws IOException {
        Path store = Paths.get("src/test/resources/archunit_store");
        if (!Files.isDirectory(store)) {
            throw new IllegalStateException("凍結ストアが見つからない: " + store.toAbsolutePath());
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(store)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    for (String className : classNames) {
                        if (line.contains(className + " ") || line.contains(className + ".")
                                || line.contains(className + "#") || line.contains(className + "(")) {
                            hits.add(file.getFileName() + ": " + line);
                        }
                    }
                }
            }
        }
        return hits;
    }
}
