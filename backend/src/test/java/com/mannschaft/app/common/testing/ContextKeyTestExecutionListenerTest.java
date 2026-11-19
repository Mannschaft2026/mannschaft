package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * CMP-261111-0508 試練（甲）K-5: {@link ContextKeyTestExecutionListener} の受け入れテスト。
 *
 * <p>入れ子の検体クラスは Testcontainers を使わない軽い {@code @SpringJUnitConfig} で、
 * 本テストが JUnit Platform Launcher で明示的に流すときだけ有効になる（Gradle の直接実行では無効＝skip）。
 * 検体は Listener を明示宣言しない。spring.factories 経由の既定登録で載ることも併せて検査する。
 * 各テストメソッドは専用の設定クラスを使い、JVM 内の ContextCache で互いに干渉しない。</p>
 */
@DisplayName("CMP-261111-0508 ContextKeyTestExecutionListener（[ci-ctx] 計測）")
class ContextKeyTestExecutionListenerTest {

    private static final Pattern LINE = Pattern.compile(
            "^\\[ci-ctx] jvm=\\S+ seq=(\\d+) class=(\\S+) key=([0-9a-f]{12}) event=(load|hit|reload|error)"
                    + " loadMs=\\d+ cacheSize=\\d+$");
    private static final Pattern ERROR_LINE = Pattern.compile("^\\[ci-ctx] .*class=(\\S+) .*event=error.*$");
    private static final String SECRET = "S3CRET-VALUE-0508";

    /** 検体を有効にするのは本テストが Launcher で流している間だけ。 */
    private static volatile boolean fixtureMode;

    static boolean fixtureMode() {
        return fixtureMode;
    }

    @Test
    void K5_spring_factoriesで既定登録され順序を明記している() throws Exception {
        assertThat(SpringFactoriesLoader.loadFactoryNames(TestExecutionListener.class, getClass().getClassLoader()))
                .contains(ContextKeyTestExecutionListener.class.getName());
        assertThat(ContextKeyTestExecutionListener.class.getDeclaredMethod("getOrder").getDeclaringClass())
                .as("getOrder を明示的に宣言すること").isEqualTo(ContextKeyTestExecutionListener.class);
    }

    @Test
    void K5_初回ロードはload_同構成の2クラス目はhit_DirtiesContext後はreloadで秘密値を出さない() {
        List<String> lines;
        TestExecutionSummary summary;
        try (ContextKeyTestExecutionListener.Capture capture = ContextKeyTestExecutionListener.startCapture()) {
            summary = run(A1LoadFirst.class, A2HitSecond.class, A3DirtyReload.class);
            lines = List.copyOf(capture.lines());
        }

        assertThat(summary.getTotalFailureCount()).as(failures(summary)).isZero();
        assertThat(summary.getTestsSucceededCount()).isEqualTo(4);
        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line).matches(LINE));
        assertThat(lines).noneMatch(line -> line.contains(SECRET));

        List<Long> seqs = lines.stream().map(line -> Long.parseLong(group(line, 1))).collect(Collectors.toList());
        assertThat(seqs).as("seq は JVM 内で単調増加").isSortedAccordingTo(Long::compare).doesNotHaveDuplicates();

        assertThat(events(lines, A1LoadFirst.class)).as("初回ロード").startsWith("load").doesNotContain("reload");
        assertThat(events(lines, A2HitSecond.class)).as("同構成の2クラス目").contains("hit")
                .doesNotContain("load", "reload");
        List<String> dirty = events(lines, A3DirtyReload.class);
        assertThat(dirty).as("@DirtiesContext(AFTER_EACH_TEST_METHOD) の2メソッド").startsWith("load")
                .contains("reload");
        assertThat(dirty.indexOf("reload")).isGreaterThan(dirty.indexOf("load"));

        String key = ContextGroupClassOrderer.contextKey(A1LoadFirst.class);
        assertThat(keys(lines, A1LoadFirst.class)).containsOnly(key);
        assertThat(keys(lines, A2HitSecond.class)).containsOnly(key);
        assertThat(keys(lines, A3DirtyReload.class))
                .containsOnly(ContextGroupClassOrderer.contextKey(A3DirtyReload.class)).doesNotContain(key);
    }

    @Test
    void K5_TestInstance_PER_CLASSでもloadとreloadを正しく出す() {
        List<String> lines;
        TestExecutionSummary summary;
        try (ContextKeyTestExecutionListener.Capture capture = ContextKeyTestExecutionListener.startCapture()) {
            summary = run(D1PerClassDirty.class);
            lines = List.copyOf(capture.lines());
        }

        assertThat(summary.getTotalFailureCount()).as(failures(summary)).isZero();
        assertThat(summary.getTestsSucceededCount()).isEqualTo(2);
        assertThat(events(lines, D1PerClassDirty.class)).as("初回はload（hitに誤分類しない）、破棄後はreload")
                .containsExactly("load", "reload");
    }

    @Test
    void K5_計測自体の例外は握ってerrorを出しテストを落とさない() {
        List<String> lines;
        TestExecutionSummary summary;
        try (ContextKeyTestExecutionListener.Capture capture = ContextKeyTestExecutionListener.startCapture()) {
            ContextKeyTestExecutionListener.injectMeasurementFailure(new IllegalStateException("検体: 計測失敗"));
            try {
                summary = run(B1MeasurementFailure.class);
            } finally {
                ContextKeyTestExecutionListener.injectMeasurementFailure(null);
            }
            lines = List.copyOf(capture.lines());
        }

        assertThat(summary.getTotalFailureCount()).as(failures(summary)).isZero();
        assertThat(summary.getTestsSucceededCount()).isEqualTo(1);
        assertThat(lines).anySatisfy(line -> {
            Matcher matcher = ERROR_LINE.matcher(line);
            assertThat(matcher.matches()).as(line).isTrue();
            assertThat(matcher.group(1)).isEqualTo(B1MeasurementFailure.class.getName());
        });
    }

    @Test
    void K5_本来のコンテキスト起動失敗はテスト失敗のまま残る() {
        TestExecutionSummary summary = run(C1BrokenContext.class);

        assertThat(summary.getTestsFoundCount()).isEqualTo(1);
        assertThat(summary.getTotalFailureCount()).isEqualTo(1);
        Throwable cause = summary.getFailures().get(0).getException();
        boolean found = false;
        for (Throwable t = cause; t != null; t = t.getCause()) {
            found |= String.valueOf(t.getMessage()).contains("検体: コンテキスト起動失敗");
        }
        assertThat(found).as("起動失敗の原因がそのまま残ること: %s", cause).isTrue();
    }

    // ---------------------------------------------------------------- 補助

    private static TestExecutionSummary run(Class<?>... fixtures) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(Arrays.stream(fixtures).map(type -> selectClass(type)).collect(Collectors.toList()))
                // :test で既定 Orderer が設定されていても、検体は名前順（A1→A2→A3）に固定して流す。
                .configurationParameter("junit.jupiter.testclass.order.default",
                        "org.junit.jupiter.api.ClassOrderer$ClassName")
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        fixtureMode = true;
        try {
            LauncherFactory.create().execute(request, listener);
        } finally {
            fixtureMode = false;
        }
        return listener.getSummary();
    }

    private static String failures(TestExecutionSummary summary) {
        return summary.getFailures().stream()
                .map(failure -> failure.getTestIdentifier().getDisplayName() + ": " + failure.getException())
                .collect(Collectors.joining("\n"));
    }

    private static String group(String line, int index) {
        Matcher matcher = LINE.matcher(line);
        assertThat(matcher.matches()).as(line).isTrue();
        return matcher.group(index);
    }

    private static List<String> events(List<String> lines, Class<?> type) {
        return lines.stream().filter(line -> group(line, 2).equals(type.getName()))
                .map(line -> group(line, 4)).collect(Collectors.toList());
    }

    private static List<String> keys(List<String> lines, Class<?> type) {
        return lines.stream().filter(line -> group(line, 2).equals(type.getName()))
                .map(line -> group(line, 3)).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- 検体

    /** A 系検体の共通構成（Bean 1 個だけの軽い構成）。 */
    @Configuration(proxyBeanMethods = false)
    static class SharedConfigurationA {
        @Bean
        String sharedA() {
            return "a";
        }
    }

    /** @DirtiesContext 検体の構成。 */
    @Configuration(proxyBeanMethods = false)
    static class DirtyConfiguration {
        @Bean
        String dirty() {
            return "d";
        }
    }

    /** 計測失敗検体の構成。 */
    @Configuration(proxyBeanMethods = false)
    static class MeasurementFailureConfiguration {
        @Bean
        String measurement() {
            return "m";
        }
    }

    /** 起動が必ず失敗する構成。 */
    @Configuration(proxyBeanMethods = false)
    static class BrokenConfiguration {
        @Bean
        String broken() {
            throw new IllegalStateException("検体: コンテキスト起動失敗");
        }
    }

    @SpringJUnitConfig(SharedConfigurationA.class)
    @TestPropertySource(properties = "ctx.listener.secret=" + SECRET)
    @EnabledIf("com.mannschaft.app.common.testing.ContextKeyTestExecutionListenerTest#fixtureMode")
    static class A1LoadFirst {
        @Test
        void loads() {
        }
    }

    @SpringJUnitConfig(SharedConfigurationA.class)
    @TestPropertySource(properties = "ctx.listener.secret=" + SECRET)
    @EnabledIf("com.mannschaft.app.common.testing.ContextKeyTestExecutionListenerTest#fixtureMode")
    static class A2HitSecond {
        @Test
        void hits() {
        }
    }

    @SpringJUnitConfig(DirtyConfiguration.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    @TestMethodOrder(MethodOrderer.MethodName.class)
    @EnabledIf("com.mannschaft.app.common.testing.ContextKeyTestExecutionListenerTest#fixtureMode")
    static class A3DirtyReload {
        @Test
        void m1Load() {
        }

        @Test
        void m2Reload() {
        }
    }

    /** PER_CLASS 検体の構成。 */
    @Configuration(proxyBeanMethods = false)
    static class PerClassConfiguration {
        @Bean
        String perClass() {
            return "p";
        }
    }

    @SpringJUnitConfig(PerClassConfiguration.class)
    @org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    @TestMethodOrder(MethodOrderer.MethodName.class)
    @EnabledIf("com.mannschaft.app.common.testing.ContextKeyTestExecutionListenerTest#fixtureMode")
    static class D1PerClassDirty {
        @Test
        void m1Load() {
        }

        @Test
        void m2Reload() {
        }
    }

    @SpringJUnitConfig(MeasurementFailureConfiguration.class)
    @EnabledIf("com.mannschaft.app.common.testing.ContextKeyTestExecutionListenerTest#fixtureMode")
    static class B1MeasurementFailure {
        @Test
        void passesEvenIfMeasurementFails() {
        }
    }

    @SpringJUnitConfig(BrokenConfiguration.class)
    @EnabledIf("com.mannschaft.app.common.testing.ContextKeyTestExecutionListenerTest#fixtureMode")
    static class C1BrokenContext {
        @Test
        void failsBecauseContextCannotStart() {
        }
    }
}
