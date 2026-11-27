package com.mannschaft.app.common.architecture;

import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人 G5（戦役 Z-A PR2 再検分指摘 1b）: 取引内の運営アカウント拒否（{@code assertJudged*} /
 * {@code isJudgedPlatformAccount}）を持つ Service の入口は、判定を置く側からしか呼ばれない。
 *
 * <p>取引の中の拒否は DB を読まず、判定が無ければ {@link IllegalStateException}（500）で止まる。
 * 判定を置き忘れた入口（Controller・段取り役・新しい呼び出し元）を CI で見つけるため、main で
 * 「取引内の拒否へ至るメソッド」（拒否を直接呼ぶメソッドと、それを呼ぶメソッドを呼び出し元へ再帰的にたどった集合。
 * たどるのは {@code callJudged} / {@code runJudged} を呼ぶクラス＝判定を置く側の手前まで）を求め、
 * そのうち<b>本番コードの誰からも呼ばれないメソッド</b>（フレームワークが直接呼ぶ入口: Controller のハンドラ・
 * スケジューラ・リスナー等）を違反とする。判定を置く側のクラスの中から呼ばれるものは、判定の範囲の中なので適合。</p>
 * <p>違反 0 件で固定する（凍結ストアは使わない）。限界: 判定を置くクラスの中で、{@code callJudged} / {@code runJudged} の外から
 * 入口を呼ぶ形は区別できない（クラス単位で見る）。その形は各 Controller の配線試験（{@code *PlatformAccountWiringTest}）が見る。</p>
 */
@DisplayName("G5 運営アカウント判定の呼出元ガード（判定を置かずに取引の入口を呼ばない）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class PlatformAccountJudgedCallerGuardArchTest {

    private static final String POLICY_FQN = "com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy";

    @Test
    @DisplayName("取引内の拒否を持つ入口は、判定を置く側（callJudged / runJudged を呼ぶクラス）からしか呼ばれない")
    void judgedEntriesAreCalledOnlyFromJudgingCallers() {
        Analysis analysis = analyze(ProductionClasses.get());

        // 番人が空振りしていないこと（対象が実在する）
        assertThat(analysis.entries).as("取引内の拒否を持つ入口が 1 件も見つからない（走査が空振り）").isNotEmpty();
        assertThat(analysis.judgingClasses).as("判定を置く側のクラスが 1 件も見つからない（走査が空振り）").isNotEmpty();

        assertThat(analysis.violations)
                .as("判定を置かずに、取引内の運営アカウント拒否を持つ入口を呼んでいる。呼び出し元で"
                        + "PlatformAccountAffiliationPolicy#judge → callJudged / runJudged の範囲に入れること")
                .isEmpty();
    }

    @Test
    @DisplayName("自己検証: 判定を置かない入口は違反、判定を置く呼び出し元から呼ばれる入口と入口同士の呼び出しは違反にならない")
    void guardDetectsMissingJudgement() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                FixtureGuardedService.class, FixtureJudgingCaller.class, FixtureForgetfulCaller.class,
                FixtureGuardedOuterService.class, FixtureRelayService.class);

        Analysis analysis = analyze(fixtures);

        assertThat(analysis.entries).anyMatch(e -> e.contains("FixtureGuardedService.enter("));
        // private の内側で拒否を呼ぶ形も、それを呼ぶ非 private メソッドが入口として拾われる
        assertThat(analysis.entries).anyMatch(e -> e.contains("FixtureGuardedService.enterViaPrivate"));
        // Service から Service へ渡す形（relay）も、呼び出し元へたどって判定を置かない最上位だけが違反になる
        assertThat(analysis.entries).anyMatch(e -> e.contains("FixtureRelayService.relay"));
        assertThat(analysis.violations)
                .hasSize(1)
                .allMatch(v -> v.contains("FixtureForgetfulCaller.call"));
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private record Analysis(Set<String> entries, Set<String> judgingClasses, List<String> violations) {
    }

    private static JavaCodeUnit resolve(JavaMethodCall call) {
        return call.getTarget().resolveMember().map(m -> (JavaCodeUnit) m).orElse(null);
    }

    private static Analysis analyze(JavaClasses classes) {
        Set<JavaMethodCall> calls = new LinkedHashSet<>();
        classes.forEach(c -> calls.addAll(c.getMethodCallsFromSelf()));

        Set<String> judging = new TreeSet<>();
        Set<JavaCodeUnit> reaching = new LinkedHashSet<>();
        for (JavaMethodCall call : calls) {
            if (!call.getTargetOwner().getName().equals(POLICY_FQN)) {
                continue;
            }
            String name = call.getTarget().getName();
            if (name.equals("callJudged") || name.equals("runJudged")) {
                judging.add(topLevel(call.getOriginOwner()).getName());
            } else if (name.startsWith("assertJudged") || name.equals("isJudgedPlatformAccount")) {
                addWithEnclosing(reaching, call.getOrigin());
            }
        }

        // 取引内の拒否へ至るメソッドを、呼び出し元へたどって広げる（Service から Service へ渡る形も含む）。
        // 判定を置く側（callJudged / runJudged を呼ぶクラス）の手前で止める: そこから先は判定の範囲の中。
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JavaMethodCall call : calls) {
                JavaCodeUnit target = resolve(call);
                if (target != null && reaching.contains(target)
                        && !judging.contains(topLevel(call.getOriginOwner()).getName())
                        && !call.getOriginOwner().getName().equals(POLICY_FQN)
                        && addWithEnclosing(reaching, call.getOrigin())) {
                    grew = true;
                }
            }
        }

        // 取引内の拒否へ至るのに、本番コードの誰からも呼ばれないメソッド＝フレームワークが直接呼ぶ入口
        // （Controller のハンドラ・スケジューラ・リスナー等）。判定を置く側ではないのでここが判定漏れ。
        Set<JavaCodeUnit> called = new LinkedHashSet<>();
        for (JavaMethodCall call : calls) {
            JavaCodeUnit target = resolve(call);
            if (target != null && !(topLevel(call.getOriginOwner()).equals(topLevel(target.getOwner()))
                    && call.getOrigin().equals(target))) {
                called.add(target);
            }
        }
        List<String> violations = new ArrayList<>();
        Set<String> entryNames = new TreeSet<>();
        for (JavaCodeUnit unit : reaching) {
            if (unit.getModifiers().contains(JavaModifier.PRIVATE) || unit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                continue;
            }
            entryNames.add(unit.getFullName());
            if (!called.contains(unit)) {
                violations.add(unit.getFullName());
            }
        }
        return new Analysis(entryNames, judging, violations);
    }

    /**
     * lambda の内側（コンパイラが作る {@code lambda$メソッド名$N}）の呼び出しは、それを書いた元のメソッドへも結びつける
     * （ArchUnit は lambda 本体と元のメソッドの呼び出し関係を持たないため）。
     */
    private static boolean addWithEnclosing(Set<JavaCodeUnit> set, JavaCodeUnit unit) {
        boolean added = set.add(unit);
        String name = unit.getName();
        if (name.startsWith("lambda$")) {
            String rest = name.substring("lambda$".length());
            int cut = rest.lastIndexOf('$');
            String enclosing = cut > 0 ? rest.substring(0, cut) : rest;
            for (JavaCodeUnit candidate : unit.getOwner().getCodeUnits()) {
                if (candidate.getName().equals(enclosing)) {
                    added |= set.add(candidate);
                }
            }
        }
        return added;
    }

    private static JavaClass topLevel(JavaClass c) {
        JavaClass current = c;
        while ((current.isAnonymousClass() || current.isLocalClass()) && current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    // ------------------------------------------------------------------
    // 自己検証用の見本（本番コードではない）
    // ------------------------------------------------------------------

    static class FixtureGuardedService {
        public void enter(Long userId) {
            PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(userId);
        }

        public void enterViaPrivate(Long userId) {
            deny(userId);
        }

        private void deny(Long userId) {
            PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(userId);
        }
    }

    /** 入口同士の呼び出し（判定範囲の中で別の入口を呼ぶ）。呼び出し元自体が入口なので違反にならない。 */
    static class FixtureGuardedOuterService {
        private final FixtureGuardedService inner = new FixtureGuardedService();

        public void outer(Long userId) {
            PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(userId);
            inner.enter(userId);
        }
    }

    static class FixtureJudgingCaller {
        private final FixtureGuardedService service = new FixtureGuardedService();
        private final FixtureGuardedOuterService outer = new FixtureGuardedOuterService();

        void call(PlatformAccountAffiliationPolicy.Verdicts verdicts) {
            PlatformAccountAffiliationPolicy.runJudged(verdicts, () -> service.enter(1L));
            PlatformAccountAffiliationPolicy.runJudged(verdicts, () -> service.enterViaPrivate(1L));
            PlatformAccountAffiliationPolicy.runJudged(verdicts, () -> outer.outer(1L));
        }
    }

    /** Service から Service へ渡す中継（それ自体は拒否を呼ばない）。 */
    static class FixtureRelayService {
        private final FixtureGuardedService service = new FixtureGuardedService();

        public void relay(Long userId) {
            service.enter(userId);
        }
    }

    /** 判定を置かずに入口を呼ぶ（置き忘れ）。 */
    static class FixtureForgetfulCaller {
        private final FixtureGuardedService service = new FixtureGuardedService();
        private final FixtureRelayService relay = new FixtureRelayService();

        void call() {
            service.enter(1L);
            service.enterViaPrivate(1L);
            relay.relay(1L);
        }
    }
}
