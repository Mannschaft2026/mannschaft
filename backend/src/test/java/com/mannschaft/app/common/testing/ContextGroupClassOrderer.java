package com.mannschaft.app.common.testing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * CMP-261111-0508（甲）: 同じ Spring テストコンテキスト構成（MergedContextConfiguration）のテストクラスを連続させる
 * JUnit Jupiter の ClassOrderer。
 *
 * <p>契約（受け入れ条件 K-1〜K-4）:</p>
 * <ul>
 *   <li>各クラスの MCC をコンテキスト起動なし・静的初期化なしで構築し、MCC が等しいクラスを連続させる。</li>
 *   <li>グループ順は各グループ内の最小 FQCN の昇順、グループ内は FQCN 昇順。入力順に依存しない。</li>
 *   <li>Spring 非使用のクラス・MCC 構築が例外になったクラスは単独グループ（実行は継続）。</li>
 *   <li>キーは MCC の構成要素（testClass を除く）を正規化した文字列の SHA-256 先頭12桁。
 *       同一性ハッシュ・testClass 名を含めないので、別 classloader・別プロセスでも同一文字列になる。</li>
 * </ul>
 *
 * <p>有効化は Gradle の {@code :test} タスクだけ（build-logic の ContextOrdererSettings が
 * {@code junit.jupiter.testclass.order.default} を渡す）。{@code -PcontextOrderer=false} で外せる。</p>
 */
public final class ContextGroupClassOrderer implements ClassOrderer {

    private static final int KEY_LENGTH = 12;
    private static final Pattern DEFAULT_TO_STRING = Pattern.compile("^[\\w.$]+@[0-9a-f]+$");
    private static final Pattern IDENTITY_HASH = Pattern.compile("@[0-9a-f]{4,}");
    private static final Pattern LAMBDA_SUFFIX = Pattern.compile("\\$\\$Lambda[\\w$./]*");

    @Override
    public void orderClasses(ClassOrdererContext context) {
        long start = System.nanoTime();
        List<? extends ClassDescriptor> descriptors = context.getClassDescriptors();
        List<Class<?>> classes = descriptors.stream().<Class<?>>map(ClassDescriptor::getTestClass)
                .collect(Collectors.toList());
        Result result = order(classes);
        Map<Class<?>, Integer> position = new HashMap<>();
        for (int i = 0; i < result.ordered().size(); i++) {
            position.put(result.ordered().get(i), i);
        }
        descriptors.sort(Comparator.comparingInt(descriptor -> position.get(descriptor.getTestClass())));
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("[ci-ctx-orderer] classes=" + classes.size() + " fallback=" + result.fallbackCount()
                + " ms=" + ms);
    }

    /**
     * 与えられたクラス列を並べ替えた結果を返す（{@link #orderClasses} の本体。入力は変更しない）。
     */
    public static Result order(List<Class<?>> classes) {
        Map<Class<?>, String> keys = new LinkedHashMap<>();
        // key -> 同一構成のクラス。単独グループ（非 Spring・構築失敗）は null キーの代わりにクラス固有の枠へ入れる。
        Map<String, List<Class<?>>> groups = new HashMap<>();
        int fallback = 0;
        int nonSpring = 0;
        for (Class<?> type : classes) {
            Analysis analysis = analyze(type);
            switch (analysis.outcome()) {
                case KEYED -> {
                    keys.put(type, analysis.key());
                    groups.computeIfAbsent("K:" + analysis.key(), k -> new ArrayList<>()).add(type);
                }
                case NON_SPRING -> {
                    nonSpring++;
                    groups.computeIfAbsent("S:" + type.getName(), k -> new ArrayList<>()).add(type);
                }
                case FAILED -> {
                    fallback++;
                    groups.computeIfAbsent("S:" + type.getName(), k -> new ArrayList<>()).add(type);
                }
            }
        }
        Comparator<Class<?>> byName = Comparator.comparing(Class::getName);
        List<List<Class<?>>> ordered = new ArrayList<>(groups.values());
        ordered.forEach(group -> group.sort(byName));
        ordered.sort((a, b) -> byName.compare(a.get(0), b.get(0)));
        List<Class<?>> flat = new ArrayList<>(classes.size());
        ordered.forEach(flat::addAll);
        return new Result(flat, keys, fallback, nonSpring);
    }

    /**
     * テストクラスのコンテキストキー（12桁の16進小文字）。Spring 非使用・MCC 構築例外なら {@code null}。
     */
    public static String contextKey(Class<?> testClass) {
        return analyze(testClass).key();
    }

    /** MCC からキーを計算する（testClass は無視する）。Listener もこれを使い、Orderer とキーを一致させる。 */
    public static String contextKey(MergedContextConfiguration configuration) {
        return sha256Prefix(normalize(configuration));
    }

    private enum Outcome { KEYED, NON_SPRING, FAILED }

    private record Analysis(Outcome outcome, String key) {
    }

    private static Analysis analyze(Class<?> type) {
        try {
            if (!usesSpringExtension(type)) {
                return new Analysis(Outcome.NON_SPRING, null);
            }
            // コンテキストは起動しない。BootstrapUtils は注釈を読むだけで、クラスの静的初期化も起こさない。
            MergedContextConfiguration configuration =
                    BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration();
            return new Analysis(Outcome.KEYED, contextKey(configuration));
        } catch (Throwable failure) { // NOSONAR 構築失敗は単独グループへ落とし、実行は継続する（K-3）。
            return new Analysis(Outcome.FAILED, null);
        }
    }

    /** Spring の拡張（SpringExtension）を使うクラスか。ContextOverrideCensus も同じ判定を使う（パッケージ内公開）。 */
    static boolean usesSpringExtension(Class<?> type) {
        // @Nested クラスは enclosing class の拡張（SpringExtension）を JUnit が継承するので、外側も辿る。
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            boolean spring = MergedAnnotations.from(current, SearchStrategy.TYPE_HIERARCHY).stream(ExtendWith.class)
                    .anyMatch(annotation -> Arrays.stream(annotation.getClassArray("value"))
                            .anyMatch(SpringExtension.class::isAssignableFrom));
            if (spring) {
                return true;
            }
        }
        return false;
    }

    /** testClass と同一性ハッシュを含まない、MCC の安定した文字列表現。 */
    private static String normalize(MergedContextConfiguration mcc) {
        StringBuilder text = new StringBuilder();
        text.append("loader=").append(mcc.getContextLoader().getClass().getName()).append('\n');
        text.append("classes=").append(names(mcc.getClasses())).append('\n');
        text.append("locations=").append(Arrays.toString(mcc.getLocations())).append('\n');
        text.append("profiles=").append(Arrays.toString(mcc.getActiveProfiles())).append('\n');
        text.append("initializers=").append(sortedNames(mcc.getContextInitializerClasses())).append('\n');
        text.append("psLocations=").append(Arrays.toString(mcc.getPropertySourceLocations())).append('\n');
        text.append("psProperties=").append(Arrays.toString(mcc.getPropertySourceProperties())).append('\n');
        text.append("psDescriptors=").append(scrub(String.valueOf(mcc.getPropertySourceDescriptors()))).append('\n');
        // customizer は Set（順序不定）なので、正規化した文字列を昇順に並べる。
        TreeMap<String, Integer> customizers = new TreeMap<>();
        for (ContextCustomizer customizer : mcc.getContextCustomizers()) {
            customizers.merge(normalizeObject(customizer), 1, Integer::sum);
        }
        text.append("customizers=").append(customizers.keySet()).append('\n');
        MergedContextConfiguration parent = mcc.getParent();
        text.append("parent=").append(parent == null ? "" : normalize(parent));
        return text.toString();
    }

    /** 反射で辿る深さの上限（循環・巨大なオブジェクトグラフを避ける）。 */
    private static final int MAX_DEPTH = 4;

    /**
     * customizer などの構成要素を、同一性ハッシュ・testClass を含まない文字列にする。
     *
     * <p>Spring の customizer（例: BeanOverrideContextCustomizer の @MockitoBean 一覧）は toString を持たず、
     * Object 既定（クラス名@同一性ハッシュ）しか出ないことが多い。クラス名だけで代替すると、@MockitoBean の型が違うクラスまで
     * 同キーになる（別構成を同グループに入れてしまう）ので、許可リスト（{@link #isTraversable}）の型は宣言フィールドを反射で辿って
     * 正規化する（深さ {@value #MAX_DEPTH} まで。辿らない型は toString、深すぎる要素はクラス名で代替する。代替した要素の中身の差はキーに現れず、
     * 影響は並べ替えの精度が落ちるだけで、実行の正しさには関わらない）。
     * {@link java.lang.reflect.Field} は宣言クラス（＝testClass）を含めず、フィールド名と型だけにする
     * （Spring の BeanOverrideHandler#equals も宣言クラスを見ない）。</p>
     */
    private static String normalizeObject(Object value) {
        try {
            return describe(value, 0);
        } catch (RuntimeException failure) {
            return value.getClass().getName();
        }
    }

    private static String describe(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        Class<?> type = value.getClass();
        if (value instanceof Class<?> clazz) {
            return clazz.getName();
        }
        if (value instanceof org.springframework.core.ResolvableType) {
            return value.toString(); // 内部に遅延計算のキャッシュ（hash 等）を持つため反射では辿らない。
        }
        if (value instanceof java.lang.reflect.Field field) {
            return "field:" + field.getName() + ":" + field.getType().getName();
        }
        if (value instanceof java.lang.reflect.Method method) {
            return method.toString();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.stream().map(element -> describe(element, depth + 1)).sorted()
                    .collect(Collectors.toList()).toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> describe(entry.getKey(), depth + 1) + "=" + describe(entry.getValue(), depth + 1))
                    .sorted().collect(Collectors.toList()).toString();
        }
        if (type.isArray()) {
            return describe(Arrays.asList(toObjectArray(value)), depth);
        }
        if (!isTraversable(type)) {
            // 反射で辿らない型は toString を使う（Object 既定の同一性ハッシュ表記ならクラス名で代替する）。
            String text = String.valueOf(value);
            return DEFAULT_TO_STRING.matcher(text).matches() ? type.getName() : scrub(text);
        }
        if (type.isSynthetic() || type.getName().contains("$$Lambda") || depth >= MAX_DEPTH) {
            return scrub(type.getName());
        }
        StringBuilder text = new StringBuilder(type.getName()).append('{');
        for (Class<?> current = type; current != null && isTraversable(current); current = current.getSuperclass()) {
            java.lang.reflect.Field[] fields = current.getDeclaredFields();
            Arrays.sort(fields, Comparator.comparing(java.lang.reflect.Field::getName));
            for (java.lang.reflect.Field field : fields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || isTestClassReference(field)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    text.append(field.getName()).append('=').append(describe(field.get(value), depth + 1)).append(';');
                } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                    text.append(field.getName()).append("=?;");
                }
            }
        }
        return text.append('}').toString();
    }

    /**
     * 反射で中身を辿ってよい型。Spring のテストコンテキスト構成（customizer・BeanOverrideHandler 等）と本プロジェクトの型だけ。
     * ロガーなど状態が刻々と変わる外部ライブラリの内部まで辿ると、同じ構成でもキーが揺れるため許可リスト方式にしている。
     */
    private static boolean isTraversable(Class<?> type) {
        String name = type.getName();
        return !type.isEnum() && (name.startsWith("org.springframework.test.")
                || name.startsWith("org.springframework.boot.test.") || name.startsWith("com.mannschaft."));
    }

    /**
     * テストクラス自身を指すフィールド（Boot の customizer が持つ testClassName 等）。MCC の同一性には使われず
     * （Spring の equals は別の key で比べる）、含めるとテストクラスごとにキーが割れて同じ構成をまとめられない。
     */
    private static boolean isTestClassReference(java.lang.reflect.Field field) {
        return field.getName().equals("testClass") || field.getName().equals("testClassName");
    }

    private static Object[] toObjectArray(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        Object[] result = new Object[length];
        for (int i = 0; i < length; i++) {
            result[i] = java.lang.reflect.Array.get(array, i);
        }
        return result;
    }

    private static String scrub(String text) {
        return LAMBDA_SUFFIX.matcher(IDENTITY_HASH.matcher(text).replaceAll("@")).replaceAll("\\$\\$Lambda");
    }

    private static String names(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getName).collect(Collectors.toList()).toString();
    }

    private static String sortedNames(Set<? extends Class<?>> types) {
        return types.stream().map(Class::getName).sorted().collect(Collectors.toList()).toString();
    }

    private static String sha256Prefix(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, KEY_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 並べ替えの結果。
     *
     * @param ordered        並べ替え後のクラス列
     * @param keys           Spring クラスのキー（Spring 非使用・構築例外のクラスは含まない）
     * @param fallbackCount  MCC 構築が例外になり単独グループへ落としたクラス数
     * @param nonSpringCount Spring 非使用で単独グループにしたクラス数
     */
    public record Result(List<Class<?>> ordered, Map<Class<?>, String> keys, int fallbackCount, int nonSpringCount) {
    }
}
