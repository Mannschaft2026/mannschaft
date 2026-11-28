package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 共通の門 {@code ScopeContentAccessGate}（F00 §20）が読み取り専用であることの番人（CMP-261124-0159 PR-D0）。
 *
 * <p>D-3T（{@link CrossDomainTransactionalTransitiveArchTest}）は門を「越境読み取り専用の認可境界」として扱い、
 * そこで展開を止める（{@link CrossDomainTransactionalTransitiveArchTest#AUTHORIZATION_BOUNDARIES}）。その前提条件
 * ——門の先で書き込みやロックが起きないこと——を本番人が機械的に保証する。前提が崩れたら境界は名乗れない。</p>
 *
 * <ol>
 *   <li>門のクラス・メソッドに {@code @Transactional} が付いていないか、付いていても {@code readOnly = true}。
 *       合成注釈（メタ注釈）経由の {@code @Transactional} は readOnly を確かめられないため違反とする。</li>
 *   <li>門のすべてのコード単位から呼び出しを推移的に辿り（interface 呼び出しは取込集合内の<b>全</b>具象実装へ展開する。
 *       D-3T の単一実装展開より保守的）、次のいずれにも到達しない。
 *       <ul>
 *         <li>Repository（{@code .repository} パッケージの型、または Spring Data {@code Repository} の派生）の
 *             {@code save*} / {@code delete*} / {@code flush*} / {@code @Modifying} の付いたメソッド</li>
 *         <li>{@code EntityManager} / Hibernate {@code Session} の {@code persist} / {@code merge} / {@code remove} /
 *             {@code flush} / {@code lock} など、JPA {@code Query#executeUpdate} / {@code setLockMode}、
 *             {@code JdbcTemplate} 系の {@code update} / {@code batchUpdate} / {@code execute}</li>
 *       </ul></li>
 *   <li>悲観ロックを使わない: 到達するメソッドに {@code @Lock} が無く、{@code @Query} に {@code FOR UPDATE} /
 *       {@code FOR SHARE} が無く、{@code LockModeType} の {@code PESSIMISTIC_*} を参照しない。</li>
 * </ol>
 *
 * <p>自己検査は {@code ScopeContentAccessGateReadOnlyGuardConditionTest}（違反するダミーの門で赤になることを確かめる）。</p>
 */
@AnalyzeClasses(packages = "com.mannschaft.app", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag(ArchUnitTestTag.ARCHUNIT)
class ScopeContentAccessGateReadOnlyArchTest {

    static final String GATE_CLASS_NAME = "com.mannschaft.app.common.ScopeContentAccessGate";

    private static final String REPOSITORY_MARKER = ".repository";
    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";
    private static final String MODIFYING = "org.springframework.data.jpa.repository.Modifying";
    private static final String LOCK = "org.springframework.data.jpa.repository.Lock";
    private static final String QUERY = "org.springframework.data.jpa.repository.Query";
    private static final String LOCK_MODE_TYPE = "jakarta.persistence.LockModeType";
    private static final Pattern REPOSITORY_WRITE = Pattern.compile("^(save|delete|flush).*");
    private static final Pattern LOCKING_SQL = Pattern.compile("(?i)\\bFOR\\s+(UPDATE|SHARE)\\b");
    private static final Map<String, Set<String>> PERSISTENCE_WRITES = Map.of(
        "jakarta.persistence.EntityManager",
            Set.of("persist", "merge", "remove", "flush", "lock", "joinTransaction"),
        "org.hibernate.Session",
            Set.of("persist", "merge", "remove", "flush", "lock", "save", "saveOrUpdate", "update", "delete"),
        "jakarta.persistence.Query", Set.of("executeUpdate", "setLockMode"),
        "jakarta.persistence.TypedQuery", Set.of("executeUpdate", "setLockMode"),
        "org.hibernate.query.Query", Set.of("executeUpdate", "setLockMode", "setHibernateLockMode"),
        "org.hibernate.query.NativeQuery", Set.of("executeUpdate", "setLockMode", "setHibernateLockMode"),
        "org.springframework.jdbc.core.JdbcTemplate", Set.of("update", "batchUpdate", "execute"),
        "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            Set.of("update", "batchUpdate", "execute"),
        "org.springframework.jdbc.core.simple.JdbcClient", Set.of("update"));

    @ArchTest
    static final ArchRule gate_should_be_read_only_authorization_port =
        classes().that().haveFullyQualifiedName(GATE_CLASS_NAME)
            .should(beReadOnlyAuthorizationPort())
            .because("F00 §20 の共通の門は D-3T の越境読み取り専用の認可境界であり（CMP-261124-0159 PR-D0）、"
                + "境界を名乗る前提として書き込み・悲観ロック・書き込みトランザクションを持ってはならない")
            .as("ScopeContentAccessGate should be a read-only authorization port (D-3T boundary precondition)");

    static ArchCondition<JavaClass> beReadOnlyAuthorizationPort() {
        return new ArchCondition<>("be a read-only authorization port") {
            private List<JavaClass> imported = List.of();

            @Override
            public void init(Collection<JavaClass> classes) {
                imported = List.copyOf(classes);
            }

            @Override
            public void check(JavaClass gate, ConditionEvents events) {
                for (String violation : findViolations(gate, imported)) {
                    events.add(SimpleConditionEvent.violated(gate, violation));
                }
            }
        };
    }

    /** 門 {@code gate} の読み取り専用違反を返す（空なら合格）。{@code candidates} は interface 展開に使う取込集合。 */
    static List<String> findViolations(JavaClass gate, Iterable<JavaClass> candidates) {
        Set<String> violations = new LinkedHashSet<>();
        checkTransactional(gate.tryGetAnnotationOfType(Transactional.class.getName()),
            gate.isMetaAnnotatedWith(Transactional.class), gate.getName(), violations);
        for (JavaMethod method : gate.getMethods()) {
            checkTransactional(method.tryGetAnnotationOfType(Transactional.class.getName()),
                method.isMetaAnnotatedWith(Transactional.class), method.getFullName(), violations);
        }
        Map<String, List<JavaClass>> implementations = implementationIndex(candidates);
        Deque<JavaCodeUnit> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        gate.getCodeUnits().stream().sorted(Comparator.comparing(JavaCodeUnit::getFullName)).forEach(unit -> {
            if (visited.add(unit.getFullName())) {
                queue.add(unit);
            }
        });
        while (!queue.isEmpty()) {
            JavaCodeUnit current = queue.removeFirst();
            checkLockAnnotations(current, violations);
            for (JavaFieldAccess access : current.getFieldAccesses()) {
                if (access.getTarget().getOwner().getName().equals(LOCK_MODE_TYPE)
                        && access.getTarget().getName().startsWith("PESSIMISTIC")) {
                    violations.add("悲観ロック " + LOCK_MODE_TYPE + "." + access.getTarget().getName()
                        + " を参照する: " + current.getFullName());
                }
            }
            for (JavaCall<?> call : current.getCallsFromSelf()) {
                JavaClass owner = call.getTarget().getOwner();
                String targetName = call.getTarget().getName();
                Optional<? extends JavaCodeUnit> resolved = call.getTarget().resolveMember();
                if (isRepository(owner) && (REPOSITORY_WRITE.matcher(targetName).matches()
                        || resolved.map(unit -> unit.isAnnotatedWith(MODIFYING)).orElse(false))) {
                    violations.add("Repository の書き込み系メソッド " + owner.getName() + "." + targetName
                        + "() へ到達する: " + current.getFullName());
                }
                if (isPersistenceWrite(owner, targetName)) {
                    violations.add("永続化の書き込み・ロック " + owner.getName() + "." + targetName
                        + "() へ到達する: " + current.getFullName());
                }
                resolved.ifPresent(unit -> checkLockAnnotations(unit, violations));
                if (!owner.getPackageName().startsWith(DomainPackages.ROOT_PACKAGE + ".")) {
                    continue;
                }
                for (JavaCodeUnit callee : resolve(call, implementations)) {
                    if (visited.add(callee.getFullName())) {
                        queue.addLast(callee);
                    }
                }
            }
        }
        return List.copyOf(violations);
    }

    private static void checkTransactional(Optional<? extends JavaAnnotation<?>> direct, boolean metaAnnotated,
            String name, Set<String> violations) {
        if (direct.isPresent()) {
            if (!Boolean.TRUE.equals(direct.get().get("readOnly").orElse(false))) {
                violations.add("@Transactional が readOnly = true でない: " + name);
            }
        } else if (metaAnnotated) {
            violations.add("合成注釈経由の @Transactional は readOnly を確かめられない: " + name);
        }
    }

    private static void checkLockAnnotations(JavaCodeUnit unit, Set<String> violations) {
        if (unit.isAnnotatedWith(LOCK)) {
            violations.add("@Lock の付いたメソッドへ到達する: " + unit.getFullName());
        }
        unit.tryGetAnnotationOfType(QUERY).flatMap(query -> query.get("value")).ifPresent(value -> {
            if (LOCKING_SQL.matcher(String.valueOf(value)).find()) {
                violations.add("@Query に FOR UPDATE / FOR SHARE がある: " + unit.getFullName());
            }
        });
    }

    private static boolean isRepository(JavaClass owner) {
        String packageName = owner.getPackageName();
        return packageName.contains(REPOSITORY_MARKER + ".") || packageName.endsWith(REPOSITORY_MARKER)
            || owner.isAssignableTo(SPRING_DATA_REPOSITORY);
    }

    private static boolean isPersistenceWrite(JavaClass owner, String methodName) {
        for (Map.Entry<String, Set<String>> entry : PERSISTENCE_WRITES.entrySet()) {
            if (entry.getValue().contains(methodName)
                    && (owner.getName().equals(entry.getKey()) || owner.isAssignableTo(entry.getKey()))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<JavaClass>> implementationIndex(Iterable<JavaClass> candidates) {
        Map<String, List<JavaClass>> index = new LinkedHashMap<>();
        for (JavaClass candidate : candidates) {
            if (!candidate.getPackageName().startsWith(DomainPackages.ROOT_PACKAGE + ".")
                    || candidate.isInterface() || candidate.getModifiers().contains(JavaModifier.ABSTRACT)) {
                continue;
            }
            for (JavaClass contract : candidate.getAllRawInterfaces()) {
                index.computeIfAbsent(contract.getName(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        return index;
    }

    /** 静的解決に加え、interface 呼び出しは取込集合内の全具象実装の本体へ展開する（保守側）。 */
    private static List<JavaCodeUnit> resolve(JavaCall<?> call, Map<String, List<JavaClass>> implementations) {
        List<JavaCodeUnit> result = new ArrayList<>();
        call.getTarget().resolveMember().ifPresent(result::add);
        JavaClass owner = call.getTarget().getOwner();
        if (!owner.isInterface()) {
            return result;
        }
        String[] parameterTypes = call.getTarget().getRawParameterTypes().stream()
            .map(JavaClass::getName).toArray(String[]::new);
        for (JavaClass implementation : implementations.getOrDefault(owner.getName(), List.of())) {
            Optional<JavaClass> current = Optional.of(implementation);
            while (current.isPresent()) {
                Optional<? extends JavaCodeUnit> body = call.getTarget().getName().equals("<init>")
                    ? Optional.empty()
                    : current.get().tryGetMethod(call.getTarget().getName(), parameterTypes);
                if (body.isPresent()) {
                    result.add(body.get());
                    break;
                }
                current = current.get().getRawSuperclass();
            }
        }
        return result;
    }
}
