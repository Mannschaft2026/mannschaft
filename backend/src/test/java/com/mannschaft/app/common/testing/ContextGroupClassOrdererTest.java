package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DefaultTestContextBootstrapper;

/**
 * CMP-261111-0508 試練（甲）K-1〜K-4: {@link ContextGroupClassOrderer} の受け入れテスト。
 *
 * <p>検体（入れ子の static クラス）は {@code @Test} を持たないので、Gradle からは実行されない。
 * MCC の構築だけを行い、コンテキストは起動しない（Docker も要らない）。</p>
 */
@DisplayName("CMP-261111-0508 ContextGroupClassOrderer（構成ごとの並べ替えとキー）")
class ContextGroupClassOrdererTest {

    private static final String KEY_PATTERN = "[0-9a-f]{12}";

    /** 静的初期化が走った回数（検体 EpsilonStaticProbe / MySqlStaticProbe が数える）。 */
    static final AtomicInteger STATIC_INIT_COUNT = new AtomicInteger();

    // ---------------------------------------------------------------- K-1

    @Test
    void K1_同じMCCの2クラスは連続し同一キーで_グループ順とグループ内はFQCN昇順() {
        ContextGroupClassOrderer.Result result = ContextGroupClassOrderer.order(
                List.of(ZetaSameX.class, GammaOther.class, BetaPlain.class, AlphaSameX.class));

        assertThat(result.ordered())
                .containsExactly(AlphaSameX.class, ZetaSameX.class, BetaPlain.class, GammaOther.class);
        assertThat(result.keys().get(AlphaSameX.class)).matches(KEY_PATTERN)
                .isEqualTo(result.keys().get(ZetaSameX.class))
                .isNotEqualTo(result.keys().get(GammaOther.class));
        assertThat(result.nonSpringCount()).isEqualTo(1);
        assertThat(result.fallbackCount()).isZero();
    }

    @Test
    void K1_customizerやプロパティ値が異なる検体は別キー() {
        assertThat(mcc(MockRunnable.class)).isNotEqualTo(mcc(MockSupplier.class));
        assertThat(ContextGroupClassOrderer.contextKey(MockRunnable.class))
                .matches(KEY_PATTERN)
                .isNotEqualTo(ContextGroupClassOrderer.contextKey(MockSupplier.class))
                .isNotEqualTo(ContextGroupClassOrderer.contextKey(AlphaSameX.class));

        assertThat(mcc(PropertyOne.class)).isNotEqualTo(mcc(PropertyTwo.class));
        assertThat(ContextGroupClassOrderer.contextKey(PropertyOne.class))
                .matches(KEY_PATTERN)
                .isNotEqualTo(ContextGroupClassOrderer.contextKey(PropertyTwo.class))
                .isNotEqualTo(ContextGroupClassOrderer.contextKey(AlphaSameX.class));
    }

    @Test
    void K1_入力順を入れ替えても出力順は同じ() {
        List<Class<?>> input = List.of(AlphaSameX.class, BetaPlain.class, GammaOther.class, ZetaSameX.class,
                MockRunnable.class, PropertyOne.class, DeltaBroken.class);
        List<Class<?>> expected = ContextGroupClassOrderer.order(input).ordered();
        for (int seed = 0; seed < 30; seed++) {
            List<Class<?>> shuffled = new ArrayList<>(input);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            assertThat(ContextGroupClassOrderer.order(shuffled).ordered()).as("seed=%d", seed)
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void K1_JupiterのClassOrderer経路でも同じ順に並べ替える() {
        List<ClassDescriptor> descriptors = new ArrayList<>();
        for (Class<?> type : List.of(ZetaSameX.class, GammaOther.class, DeltaBroken.class, BetaPlain.class,
                AlphaSameX.class)) {
            ClassDescriptor descriptor = mock(ClassDescriptor.class);
            when(descriptor.getTestClass()).thenAnswer(invocation -> type);
            when(descriptor.getDisplayName()).thenReturn(type.getSimpleName());
            descriptors.add(descriptor);
        }
        ClassOrdererContext context = mock(ClassOrdererContext.class);
        when(context.getClassDescriptors()).thenAnswer(invocation -> descriptors);

        assertThatCode(() -> new ContextGroupClassOrderer().orderClasses(context)).doesNotThrowAnyException();

        assertThat(descriptors.stream().map(ClassDescriptor::getTestClass).collect(Collectors.toList()))
                .containsExactly(AlphaSameX.class, ZetaSameX.class, BetaPlain.class, DeltaBroken.class,
                        GammaOther.class);
    }

    // ---------------------------------------------------------------- K-2

    @Test
    void K2_AbstractMySqlIntegrationTest継承の実在2クラスはMCC構築が成功し同一キーで連続する() throws Exception {
        Class<?> export = existing("circulation.CirculationExportScopeContractIT");
        Class<?> writeAcl = existing("circulation.CirculationWriteAclScopeContractIT");
        Class<?> otherConfig = existing("filesharing.PublicFileLinkContractIT");
        assertThat(AbstractMySqlIntegrationTest.class).isAssignableFrom(export).isAssignableFrom(writeAcl);

        ContextGroupClassOrderer.Result result =
                ContextGroupClassOrderer.order(List.of(writeAcl, otherConfig, export));

        assertThat(result.fallbackCount()).as("MCC 構築の例外で単独グループへ落ちた件数").isZero();
        assertThat(result.nonSpringCount()).isZero();
        assertThat(result.keys()).containsKeys(export, writeAcl, otherConfig);
        assertThat(result.keys().get(export)).matches(KEY_PATTERN).isEqualTo(result.keys().get(writeAcl))
                .isNotEqualTo(result.keys().get(otherConfig));
        int exportIndex = result.ordered().indexOf(export);
        assertThat(result.ordered().get(exportIndex + 1)).as("同じ構成の2クラスは連続する").isEqualTo(writeAcl);
    }

    // ---------------------------------------------------------------- K-3

    @Test
    void K3_MCC構築が例外になるクラスは単独グループで並べ替えは継続する() {
        ContextGroupClassOrderer.Result result =
                ContextGroupClassOrderer.order(List.of(ZetaSameX.class, DeltaBroken.class, AlphaSameX.class));

        assertThat(result.fallbackCount()).isEqualTo(1);
        assertThat(result.keys()).doesNotContainKey(DeltaBroken.class);
        assertThat(result.ordered()).containsExactly(AlphaSameX.class, ZetaSameX.class, DeltaBroken.class);
        assertThat(ContextGroupClassOrderer.contextKey(DeltaBroken.class)).isNull();
    }

    @Test
    void K3_並べ替えとキー計算で検体の静的初期化子を実行しない() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        Class<?> probe = Class.forName(EpsilonStaticProbe.class.getName(), false, loader);
        Class<?> mySqlProbe = Class.forName(MySqlStaticProbe.class.getName(), false, loader);
        int before = STATIC_INIT_COUNT.get();

        ContextGroupClassOrderer.Result result =
                ContextGroupClassOrderer.order(List.of(probe, mySqlProbe, AlphaSameX.class));
        ContextGroupClassOrderer.contextKey(probe);
        ContextGroupClassOrderer.contextKey(mySqlProbe);

        assertThat(STATIC_INIT_COUNT.get() - before).as("静的初期化の回数").isZero();
        assertThat(result.fallbackCount()).isZero();
        assertThat(result.keys()).containsKeys(probe, mySqlProbe);
    }

    // ---------------------------------------------------------------- K-4

    @Test
    void K4_testClassだけが違う同MCCは同キーでキーにクラス名を含まない() {
        assertThat(mcc(AlphaSameX.class).getTestClass()).isNotEqualTo(mcc(ZetaSameX.class).getTestClass());
        String key = ContextGroupClassOrderer.contextKey(AlphaSameX.class);
        assertThat(key).matches(KEY_PATTERN).isEqualTo(ContextGroupClassOrderer.contextKey(ZetaSameX.class));
        assertThat(ContextGroupClassOrderer.contextKey(mcc(AlphaSameX.class))).isEqualTo(key);
    }

    @Test
    void K4_別classloaderでも同じキー文字列になる() throws Exception {
        URL[] urls = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(entry -> {
                    try {
                        return new File(entry).toURI().toURL();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);
        try (URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> orderer = Class.forName(ContextGroupClassOrderer.class.getName(), false, isolated);
            assertThat(orderer).isNotSameAs(ContextGroupClassOrderer.class);
            Method contextKey = orderer.getMethod("contextKey", Class.class);
            for (Class<?> type : List.of(AlphaSameX.class, MockRunnable.class, PropertyOne.class)) {
                Class<?> isolatedType = Class.forName(type.getName(), false, isolated);
                assertThat((String) contextKey.invoke(null, isolatedType)).as(type.getSimpleName())
                        .isEqualTo(ContextGroupClassOrderer.contextKey(type));
            }
        }
    }

    @Test
    void K4_別プロセスでも同じキー文字列になる(@TempDir Path temp) throws Exception {
        List<Class<?>> types = List.of(AlphaSameX.class, MockRunnable.class, PropertyOne.class);
        Path argFile = temp.resolve("java-args.txt");
        String classPath = System.getProperty("java.class.path").replace('\\', '/');
        List<String> args = new ArrayList<>();
        args.add("-cp");
        args.add("\"" + classPath + "\"");
        args.add(ContextGroupClassOrdererTest.class.getName());
        types.forEach(type -> args.add(type.getName()));
        Files.write(argFile, args, StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "@" + argFile).redirectErrorStream(true).start();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("子プロセスが時間内に終わること").isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();

        List<String> expected = types.stream()
                .map(type -> "KEY " + type.getName() + " " + ContextGroupClassOrderer.contextKey(type))
                .collect(Collectors.toList());
        List<String> actual = output.lines().filter(line -> line.startsWith("KEY ")).collect(Collectors.toList());
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    /** K-4 の別プロセス側の入口。引数の FQCN ごとに {@code KEY <FQCN> <key>} を出す。 */
    public static void main(String[] args) throws Exception {
        ClassLoader loader = ContextGroupClassOrdererTest.class.getClassLoader();
        for (String name : args) {
            Class<?> type = Class.forName(name, false, loader);
            System.out.println("KEY " + name + " " + ContextGroupClassOrderer.contextKey(type));
        }
    }

    // ---------------------------------------------------------------- 補助

    private static MergedContextConfiguration mcc(Class<?> type) {
        return BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration();
    }

    private static Class<?> existing(String name) throws ClassNotFoundException {
        return Class.forName("com.mannschaft.app." + name, false, ContextGroupClassOrdererTest.class.getClassLoader());
    }

    /** コンテキストを起動しない metadata 専用の設定。 */
    static class ConfigurationX {
    }

    /** 別構成の設定。 */
    static class ConfigurationY {
    }

    /** 同構成 X（最小 FQCN）。 */
    @SpringJUnitConfig(ConfigurationX.class)
    static class AlphaSameX {
    }

    /** 同構成 X（別名）。 */
    @SpringJUnitConfig(ConfigurationX.class)
    static class ZetaSameX {
    }

    /** Spring 非使用。 */
    static class BetaPlain {
    }

    /** 別構成 Y。 */
    @SpringJUnitConfig(ConfigurationY.class)
    static class GammaOther {
    }

    /** customizer（@MockitoBean の型）が違う検体 1。 */
    @SpringJUnitConfig(ConfigurationX.class)
    static class MockRunnable {
        @MockitoBean
        Runnable dependency;
    }

    /** customizer（@MockitoBean の型）が違う検体 2。 */
    @SpringJUnitConfig(ConfigurationX.class)
    static class MockSupplier {
        @MockitoBean
        Supplier<String> dependency;
    }

    /** @TestPropertySource の値が違う検体 1。 */
    @SpringJUnitConfig(ConfigurationX.class)
    @TestPropertySource(properties = "ctx.orderer.sample=1")
    static class PropertyOne {
    }

    /** @TestPropertySource の値が違う検体 2。 */
    @SpringJUnitConfig(ConfigurationX.class)
    @TestPropertySource(properties = "ctx.orderer.sample=2")
    static class PropertyTwo {
    }

    /** MCC 構築が必ず例外になる bootstrapper。 */
    public static class ThrowingBootstrapper extends DefaultTestContextBootstrapper {
        @Override
        public MergedContextConfiguration buildMergedContextConfiguration() {
            throw new IllegalStateException("検体: MCC 構築失敗");
        }
    }

    /** MCC 構築が例外になる検体。 */
    @SpringJUnitConfig(ConfigurationX.class)
    @BootstrapWith(ThrowingBootstrapper.class)
    static class DeltaBroken {
    }

    /** 静的初期化子にカウンタを持つ検体（素の Spring 構成）。 */
    @SpringJUnitConfig(ConfigurationX.class)
    static class EpsilonStaticProbe {
        static {
            STATIC_INIT_COUNT.incrementAndGet();
        }
    }

    /** 静的初期化子にカウンタを持つ検体（MySQL 共通基底。初期化されると Testcontainers が起動してしまう）。 */
    static class MySqlStaticProbe extends AbstractMySqlIntegrationTest {
        static {
            STATIC_INIT_COUNT.incrementAndGet();
        }
    }
}
