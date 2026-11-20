package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.properties.AnnotationsPropertySource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

/**
 * CMP-261111-0508 追撃① 試練 AC-2(a): {@code addFilters} の実効値。
 *
 * <p>第一陣 A は基底 {@link AbstractMySqlIntegrationTest} に {@code @AutoConfigureMockMvc}（addFilters=true）を付ける。
 * その後も、直接の宣言・中間基底・合成注釈・@Nested の外側で false を宣言しているクラスは false のまま、
 * それ以外の派生（旧「基底のみ」を含む）は true であることを検査する。</p>
 *
 * <p>実効値は Spring Boot が実際に環境へ入れる値（MCC の PropertyMappingContextCustomizer が持つ
 * {@code spring.test.mockmvc.*}）から読む。{@code add-filters} は既定値（true）のとき書かれない（SkipPropertyMapping.ON_DEFAULT_VALUE）ので、
 * 「{@code spring.test.mockmvc.*} が1つも無い＝MockMvc の自動構成が無い」「{@code add-filters=false}＝false」「それ以外＝true」と読む。</p>
 *
 * <p><b>A の前は red</b>（旧「基底のみ」の派生に MockMvc の自動構成が無い）。検体（中間基底・合成注釈・@Nested）は A の前から green。</p>
 */
@DisplayName("CMP-261111-0508 追撃① AC-2(a) addFilters の実効値")
class MockMvcAddFiltersEffectiveValueTest {

    /** 全派生の走査で MCC 経由の実効値とも突き合わせる件数。 */
    private static final int CROSS_CHECK = 30;

    /** 実効値。 */
    enum Effective { TRUE, FALSE, ABSENT }

    static Effective effective(Class<?> type) throws ReflectiveOperationException {
        return read(ContextOverrideCensus.mockMvcProperties(ContextOverrideCensus.mergedConfiguration(type)));
    }

    /**
     * 全派生の走査用の軽い読み方。Boot の PropertyMappingContextCustomizerFactory がテストクラスから customizer を作るときと
     * 同じ {@link AnnotationsPropertySource} を直接作る（MCC 全体の構築を省く）。{@link #effective} と一致することは検体で確かめる。
     */
    static Effective effectiveFromAnnotations(Class<?> type) {
        AnnotationsPropertySource source = new AnnotationsPropertySource(type);
        Map<String, Object> properties = new java.util.TreeMap<>();
        for (String name : source.getPropertyNames()) {
            if (name.startsWith("spring.test.mockmvc.")) {
                properties.put(name, source.getProperty(name));
            }
        }
        return read(properties);
    }

    private static Effective read(Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return Effective.ABSENT;
        }
        Object addFilters = properties.get("spring.test.mockmvc.add-filters");
        return addFilters != null && !Boolean.parseBoolean(String.valueOf(addFilters)) ? Effective.FALSE : Effective.TRUE;
    }

    /**
     * 宣言から求めた期待値。自クラス→親クラス…の順、@Nested なら次に外側クラスを同様に辿り、最初に見つかった
     * {@code @AutoConfigureMockMvc}（合成注釈経由を含む）の addFilters を採る。宣言が無ければ true（A の後は基底が true を宣言する）。
     */
    static boolean expected(Class<?> type) {
        for (Class<?> scope = type; scope != null; scope = enclosingForNested(scope)) {
            for (Class<?> current = scope; current != null && current != Object.class; current = current.getSuperclass()) {
                MergedAnnotation<AutoConfigureMockMvc> annotation = MergedAnnotations.from(current, SearchStrategy.DIRECT)
                        .get(AutoConfigureMockMvc.class);
                if (annotation.isPresent()) {
                    return annotation.getBoolean("addFilters");
                }
            }
        }
        return true;
    }

    private static Class<?> enclosingForNested(Class<?> type) {
        return type.isAnnotationPresent(Nested.class) && !Modifier.isStatic(type.getModifiers())
                ? type.getEnclosingClass() : null;
    }

    static boolean derivesFromMySqlBase(Class<?> type) {
        for (Class<?> scope = type; scope != null; scope = enclosingForNested(scope)) {
            if (AbstractMySqlIntegrationTest.class.isAssignableFrom(scope)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("AC-2(a): false を宣言する派生は false のまま、それ以外の AbstractMySqlIntegrationTest 派生は true（A の前は red）")
    void 全派生のaddFiltersの実効値が宣言どおり() throws IOException {
        Map<String, String> unloadable = new java.util.TreeMap<>();
        List<Class<?>> classes = ContextOverrideCensus.testClasses(ContextOverrideCensus.testClassesRoot(), unloadable);
        assertThat(unloadable).as("読み込めないテストクラス").isEmpty();
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        int declaredFalse = 0;
        for (Class<?> type : classes) {
            if (!derivesFromMySqlBase(type)) {
                continue;
            }
            checked++;
            boolean want = expected(type);
            declaredFalse += want ? 0 : 1;
            Effective actual = effectiveFromAnnotations(type);
            if (checked <= CROSS_CHECK) {
                // 先頭の一部は MCC 経由の実効値と突き合わせ、軽い読み方が Spring の適用値とずれていないことを確かめる。
                try {
                    Effective viaMcc = effective(type);
                    if (viaMcc != actual) {
                        mismatches.add(type.getName() + " 読み方の不一致 MCC=" + viaMcc + " 注釈=" + actual);
                    }
                } catch (ReflectiveOperationException failure) {
                    mismatches.add(type.getName() + " MCC の実効値を読めない: " + failure);
                }
            }
            Effective wanted = want ? Effective.TRUE : Effective.FALSE;
            if (actual != wanted) {
                mismatches.add(type.getName() + " 期待=" + wanted + " 実効=" + actual);
            }
        }
        System.out.println("[ctx-census] addFilters checked=" + checked + " declaredFalse=" + declaredFalse
                + " mismatches=" + mismatches.size());

        assertThat(checked).as("AbstractMySqlIntegrationTest 派生のテストクラス").isPositive();
        assertThat(declaredFalse).as("addFilters=false を宣言する派生").isPositive();
        assertThat(mismatches)
                .as("addFilters の実効値が宣言と食い違う派生 %d 件（ABSENT は MockMvc の自動構成が無い＝第一陣 A 未実装）",
                        mismatches.size())
                .isEmpty();
    }

    @Test
    @DisplayName("AC-2(a) 検体: 直接・中間基底・合成注釈・@Nested の外側で宣言した false は実効値も false")
    void 検体_false宣言の4形式は実効値もfalse() throws ReflectiveOperationException {
        for (Class<?> specimen : List.of(DirectFalse.class, ViaIntermediateBase.class, ViaComposed.class,
                NestedOuterFalse.Inner.class)) {
            assertThat(expected(specimen)).as(specimen.getName()).isFalse();
            assertThat(effective(specimen)).as(specimen.getName()).isEqualTo(Effective.FALSE);
            assertThat(effectiveFromAnnotations(specimen)).as(specimen.getName()).isEqualTo(effective(specimen));
        }
    }

    @Test
    @DisplayName("AC-2(a) 検体: 明示の true は実効値も true、宣言の無い派生は期待 true（A の前は実効 ABSENT）")
    void 検体_true宣言と基底のみ() throws ReflectiveOperationException {
        assertThat(expected(DirectTrue.class)).isTrue();
        assertThat(effective(DirectTrue.class)).isEqualTo(Effective.TRUE);
        assertThat(effectiveFromAnnotations(DirectTrue.class)).isEqualTo(Effective.TRUE);
        assertThat(effectiveFromAnnotations(BaseOnly.class)).as("軽い読み方も MCC と一致する").isEqualTo(effective(BaseOnly.class));
        assertThat(expected(BaseOnly.class)).isTrue();
        assertThat(effective(BaseOnly.class)).as("基底のみの派生（A の前は ABSENT で red）").isEqualTo(Effective.TRUE);
    }

    // ---------------------------------------------------------------- 検体（テストメソッドを持たない。静的初期化もしない）

    @AutoConfigureMockMvc(addFilters = false)
    static class DirectFalse extends AbstractMySqlIntegrationTest {
    }

    @AutoConfigureMockMvc(addFilters = false)
    abstract static class FalseIntermediateBase extends AbstractMySqlIntegrationTest {
    }

    static class ViaIntermediateBase extends FalseIntermediateBase {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @AutoConfigureMockMvc(addFilters = false)
    @interface NoFilterMockMvc {
    }

    @NoFilterMockMvc
    static class ViaComposed extends AbstractMySqlIntegrationTest {
    }

    @AutoConfigureMockMvc(addFilters = false)
    static class NestedOuterFalse extends AbstractMySqlIntegrationTest {
        @Nested
        class Inner {
        }
    }

    @AutoConfigureMockMvc
    static class DirectTrue extends AbstractMySqlIntegrationTest {
    }

    static class BaseOnly extends AbstractMySqlIntegrationTest {
    }
}
