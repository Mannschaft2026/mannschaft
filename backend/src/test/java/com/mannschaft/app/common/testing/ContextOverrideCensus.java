package com.mannschaft.app.common.testing;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.platform.commons.annotation.Testable;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.bean.override.BeanOverrideHandler;

/**
 * CMP-261111-0508 追撃①: テストクラスごとに「Spring が実際に適用する BeanOverride」と構成キーを求める棚卸し（census）。
 *
 * <p>求め方は {@link ContextGroupClassOrderer} と同じく、{@link BootstrapUtils} で MCC を構築し（コンテキストは起動しない・
 * 静的初期化も起こさない）、その {@link ContextCustomizer} のうち BeanOverride 系の customizer が持つ handlers を読む。
 * {@code BeanOverrideHandler.forTestClass} は外側クラス（@Nested）とクラス上の宣言を探索しないので使わない。</p>
 *
 * <p>分類（受け入れ条件 AC-3。互いに排他）: {@link Status#NON_SPRING}（Spring 非使用）・{@link Status#ZERO}（override 0 件。正常な結果）・
 * {@link Status#OVERRIDES}（override 1 件以上）・{@link Status#FAILED}（解析失敗。握りつぶさず理由を保持する）。</p>
 *
 * <p>凍結スナップショット（{@value #SNAPSHOT_RESOURCE}）との照合は {@link #violations}。生成手順は
 * {@code docs/conventions/backend/31-ci-shard-weighting.md} の「構成キーの棚卸しと認可 override の番人」節。</p>
 */
public final class ContextOverrideCensus {

    /** スナップショット行の分類（Spring 対象。ZERO・OVERRIDES を区別しない）。 */
    static final String SPRING_ROW = "SPRING";

    /** 凍結スナップショットのクラスパス上の位置。 */
    public static final String SNAPSHOT_RESOURCE = "context-override-census/snapshot.tsv";

    /** 番人が毎回書き出す実測スナップショット（backend/build 配下。再生成ではこれを上の位置へ写す）。 */
    public static final String ACTUAL_OUTPUT = "context-override-census/snapshot.tsv";

    private static final String BEAN_OVERRIDE_CUSTOMIZER =
            "org.springframework.test.context.bean.override.BeanOverrideContextCustomizer";
    private static final String BOOT_MOCKITO_CUSTOMIZER =
            "org.springframework.boot.test.mock.mockito.MockitoContextCustomizer";
    private static final String PROPERTY_MAPPING_CUSTOMIZER =
            "org.springframework.boot.test.autoconfigure.properties.PropertyMappingContextCustomizer";

    /**
     * 認可型の denylist（単純名の完全一致）。認可・認証の判定を持つ型を mock すると、その IT は認可を検査しなくなる。
     */
    public static final Set<String> AUTHZ_EXACT = Set.of(
            "AccessGuard", "AccessControlService", "AuthTokenService", "UserLocaleCache",
            "ScopeConcealingAccessGate", "UserRoleRepository");

    /** 認可型の denylist（単純名の接尾辞）。 */
    public static final List<String> AUTHZ_SUFFIXES = List.of("Guard", "Authorizer", "PermissionEvaluator");

    /**
     * 認可判定を持つ型の明示リスト（単純名）。名前の規則では拾えないが、認可の根拠になっている型。
     * <ul>
     *   <li>GoogleCalendarWebhookService: Webhook の受理可否（チャネルトークン照合）を判定する（GoogleCalendarWebhookController:26-30）。</li>
     * </ul>
     */
    public static final Set<String> AUTHZ_EXPLICIT = Set.of("GoogleCalendarWebhookService");

    private ContextOverrideCensus() {
    }

    /** 解析の分類。 */
    public enum Status { NON_SPRING, ZERO, OVERRIDES, FAILED }

    /**
     * 1 クラスの解析結果。
     *
     * @param className FQCN（ネストは {@code $} 区切り）
     * @param status    分類
     * @param key       構成キー（{@link ContextGroupClassOrderer#contextKey}。NON_SPRING・FAILED は null）
     * @param overrides 実効 override（{@code 種別:型[#bean名]} の昇順。重複は残す）
     * @param failure   FAILED の理由（例外クラス名と要約）
     */
    public record Entry(String className, Status status, String key, List<String> overrides, String failure) {

        /** 認可型の override（{@link #isAuthzOverride}）。 */
        public List<String> authzOverrides() {
            return overrides.stream().filter(ContextOverrideCensus::isAuthzOverride).collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 1 クラスを解析する。例外は FAILED として理由ごと返す（握りつぶさない）。 */
    public static Entry analyze(Class<?> type) {
        try {
            if (!ContextGroupClassOrderer.usesSpringExtension(type)) {
                return new Entry(type.getName(), Status.NON_SPRING, null, List.of(), null);
            }
            MergedContextConfiguration configuration = mergedConfiguration(type);
            List<String> overrides = overrides(configuration);
            String key = ContextGroupClassOrderer.contextKey(configuration);
            return new Entry(type.getName(), overrides.isEmpty() ? Status.ZERO : Status.OVERRIDES, key, overrides, null);
        } catch (Throwable failure) { // NOSONAR 解析失敗は FAILED として名前付きで保持し、番人が判定する（AC-3）。
            return failed(type.getName(), failure);
        }
    }

    /** コンテキストを起動せずに MCC を構築する（Orderer と同じ経路）。 */
    public static MergedContextConfiguration mergedConfiguration(Class<?> type) {
        return BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration();
    }

    private static Entry failed(String className, Throwable failure) {
        String message = String.valueOf(failure.getMessage());
        if (message.length() > 120) {
            message = message.substring(0, 120);
        }
        return new Entry(className, Status.FAILED, null, List.of(),
                failure.getClass().getSimpleName() + ": " + message.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
    }

    /** MCC（親を含む）の customizer から実効 override を読む。読めない BeanOverride 系 customizer は例外にする。 */
    public static List<String> overrides(MergedContextConfiguration configuration) throws ReflectiveOperationException {
        List<String> result = new ArrayList<>();
        for (MergedContextConfiguration current = configuration; current != null; current = current.getParent()) {
            for (ContextCustomizer customizer : current.getContextCustomizers()) {
                String name = customizer.getClass().getName();
                if (name.equals(BEAN_OVERRIDE_CUSTOMIZER)) {
                    for (Object handler : (Collection<?>) readField(customizer, "handlers")) {
                        result.add(describe((BeanOverrideHandler) handler));
                    }
                } else if (name.equals(BOOT_MOCKITO_CUSTOMIZER)) {
                    for (Object definition : (Collection<?>) readField(customizer, "definitions")) {
                        result.add(describeBootDefinition(definition));
                    }
                } else {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("beanoverride") || lower.contains("mockito")) {
                        throw new IllegalStateException("読み方を知らない BeanOverride 系 customizer: " + name);
                    }
                }
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static String describe(BeanOverrideHandler handler) {
        String kind = switch (handler.getClass().getSimpleName()) {
            case "MockitoBeanOverrideHandler" -> "mock";
            case "MockitoSpyBeanOverrideHandler" -> "spy";
            case "TestBeanOverrideHandler" -> "testbean";
            default -> handler.getClass().getSimpleName();
        };
        String beanName = handler.getBeanName();
        return kind + ":" + handler.getBeanType() + (beanName == null ? "" : "#" + beanName);
    }

    private static String describeBootDefinition(Object definition) throws ReflectiveOperationException {
        String simple = definition.getClass().getSimpleName();
        boolean spy = simple.equals("SpyDefinition");
        Object type = readField(definition, spy ? "typeToSpy" : "typeToMock");
        Object beanName = readField(definition, "name");
        return (spy ? "boot-spy:" : "boot-mock:") + (type instanceof ResolvableType resolvable ? resolvable : type)
                + (beanName == null ? "" : "#" + beanName);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException next) {
                // 親クラスを探す
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "#" + name);
    }

    /**
     * MCC の {@code @AutoConfigureMockMvc} 系プロパティ（Boot の PropertyMappingContextCustomizer が実際に環境へ入れる値）。
     * 空なら MockMvc の自動構成は宣言されていない。
     */
    public static Map<String, Object> mockMvcProperties(MergedContextConfiguration configuration)
            throws ReflectiveOperationException {
        Map<String, Object> result = new TreeMap<>();
        for (ContextCustomizer customizer : configuration.getContextCustomizers()) {
            if (customizer.getClass().getName().equals(PROPERTY_MAPPING_CUSTOMIZER)) {
                Object source = readField(customizer, "propertySource");
                if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        if (name.startsWith("spring.test.mockmvc.")) {
                            result.put(name, ((PropertySource<?>) enumerable).getProperty(name));
                        }
                    }
                } else {
                    throw new IllegalStateException("PropertyMappingContextCustomizer の propertySource を列挙できない: " + source);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 認可型

    /** override 記述（{@code 種別:型[#bean名]}）が認可型か。 */
    public static boolean isAuthzOverride(String override) {
        return isAuthzType(typeOf(override));
    }

    /** 型名（FQCN。総称型の引数は無視する）が認可型か。 */
    public static boolean isAuthzType(String typeName) {
        String raw = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        String simple = raw.substring(Math.max(raw.lastIndexOf('.'), raw.lastIndexOf('$')) + 1);
        return AUTHZ_EXACT.contains(simple) || AUTHZ_EXPLICIT.contains(simple)
                || AUTHZ_SUFFIXES.stream().anyMatch(simple::endsWith);
    }

    private static String typeOf(String override) {
        String rest = override.substring(override.indexOf(':') + 1);
        int hash = rest.indexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    // ------------------------------------------------------------------ 走査

    /** テストクラスの出力ディレクトリ（本クラスを含む backend/build/classes/java/test）。 */
    public static Path testClassesRoot() {
        try {
            return Paths.get(ContextOverrideCensus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * テスト出力ディレクトリの全 .class を読み込み（静的初期化はしない）、JUnit のテストクラスを返す。
     * 読み込めないクラスは {@code unloadable} に名前と理由を入れる（黙って落とさない）。
     */
    public static List<Class<?>> testClasses(Path root, Map<String, String> unloadable) throws IOException {
        ClassLoader loader = ContextOverrideCensus.class.getClassLoader();
        List<String> names;
        try (Stream<Path> files = Files.walk(root)) {
            names = files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> root.relativize(path).toString().replace('\\', '/').replace('/', '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> !name.endsWith("package-info") && !name.endsWith("module-info"))
                    .sorted().collect(Collectors.toList());
        }
        List<Class<?>> result = new ArrayList<>();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                if (isTestClass(type)) {
                    result.add(type);
                }
            } catch (Throwable failure) { // NOSONAR 読めないクラスは名前付きで報告する（AC-3）。
                unloadable.put(name, failure.getClass().getSimpleName());
            }
        }
        return result;
    }

    /**
     * JUnit Jupiter がテストクラスとして実行するクラスか（具象・非 private・static メンバーか @Nested・テストメソッドを
     * 継承込みで持つか、テストを持つ @Nested を内包する）。
     */
    public static boolean isTestClass(Class<?> type) {
        if (type.isInterface() || type.isAnnotation() || type.isEnum() || type.isRecord() || type.isAnonymousClass()
                || type.isLocalClass() || type.isSynthetic()) {
            return false;
        }
        int modifiers = type.getModifiers();
        if (Modifier.isAbstract(modifiers) || Modifier.isPrivate(modifiers)) {
            return false;
        }
        if (type.isMemberClass() && !Modifier.isStatic(modifiers) && !type.isAnnotationPresent(Nested.class)) {
            return false;
        }
        return hasTestMethods(type) || hasNestedTests(type);
    }

    private static boolean hasTestMethods(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers())
                        && MergedAnnotations.from(method).isPresent(Testable.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNestedTests(Class<?> type) {
        for (Class<?> member : type.getDeclaredClasses()) {
            if (member.isAnnotationPresent(Nested.class) && !Modifier.isStatic(member.getModifiers())
                    && (hasTestMethods(member) || hasNestedTests(member))) {
                return true;
            }
        }
        return false;
    }

    /** 実測の棚卸し（全テストクラス + 読み込めないクラス）。 */
    public static Census take() throws IOException {
        long start = System.nanoTime();
        Map<String, String> unloadable = new TreeMap<>();
        List<Class<?>> classes = testClasses(testClassesRoot(), unloadable);
        long scanned = System.nanoTime();
        List<Entry> entries = new ArrayList<>();
        classes.forEach(type -> entries.add(analyze(type)));
        System.out.println("[ctx-census] scanMs=" + (scanned - start) / 1_000_000L + " analyzeMs="
                + (System.nanoTime() - scanned) / 1_000_000L + " testClasses=" + classes.size());
        unloadable.forEach((name, reason) -> entries.add(
                new Entry(name, Status.FAILED, null, List.of(), "クラスを読み込めない: " + reason)));
        entries.sort(Comparator.comparing(Entry::className));
        return new Census(entries);
    }

    // ------------------------------------------------------------------ 棚卸し・スナップショット

    /** 棚卸し結果。 */
    public record Census(List<Entry> entries) {

        public Map<String, Entry> byName() {
            Map<String, Entry> map = new LinkedHashMap<>();
            entries.forEach(entry -> map.put(entry.className(), entry));
            return map;
        }

        public long count(Status status) {
            return entries.stream().filter(entry -> entry.status() == status).count();
        }

        /** Spring 対象（ZERO・OVERRIDES）の distinct な構成キー数。 */
        public int distinctKeys() {
            return (int) entries.stream().filter(entry -> entry.key() != null).map(Entry::key).distinct().count();
        }

        /** 認可型 override の総件数。 */
        public int authzOverrideCount() {
            return entries.stream().mapToInt(entry -> entry.authzOverrides().size()).sum();
        }

        /** 1 件追加した棚卸し（検体用）。 */
        public Census with(Entry extra) {
            List<Entry> list = new ArrayList<>(entries);
            list.removeIf(entry -> entry.className().equals(extra.className()));
            list.add(extra);
            list.sort(Comparator.comparing(Entry::className));
            return new Census(list);
        }

        /** 1 件除いた棚卸し（検体用）。 */
        public Census without(String className) {
            return new Census(entries.stream().filter(entry -> !entry.className().equals(className))
                    .collect(Collectors.toList()));
        }

        /** 集計行（AC-3: 非 Spring・0 件・override あり・解析失敗を別々に数える）。 */
        public String summary() {
            return "classes=" + entries.size() + " nonSpring=" + count(Status.NON_SPRING) + " zero=" + count(Status.ZERO)
                    + " overrides=" + count(Status.OVERRIDES) + " failed=" + count(Status.FAILED)
                    + " distinctKeys=" + distinctKeys() + " authzOverrides=" + authzOverrideCount();
        }

        /**
         * スナップショット形式（TSV）。非 Spring のクラスは行に含めず件数だけ持つ（判定対象は Spring のクラスと解析失敗）。
         * 行: {@code FQCN <TAB> SPRING <TAB> 認可型 override...}（FAILED は理由を 3 列目に置く）。
         * 認可型ではない override（外部アダプタの mock など）は載せない。増減しても番人は red にならず、再生成も要らない。
         */
        public String toSnapshot(int distinctKeysBaseline) {
            StringBuilder text = new StringBuilder();
            text.append("# CMP-261111-0508 追撃①: テストコンテキストの BeanOverride 棚卸し（凍結スナップショット）。手で編集しない。\n");
            text.append("# 再生成: docs/conventions/backend/31-ci-shard-weighting.md の「構成キーの棚卸しと認可 override の番人」節。\n");
            text.append("# summary ").append(summary()).append('\n');
            text.append("!distinctKeysBaseline=").append(distinctKeysBaseline).append('\n');
            text.append("!authzOverrideBaseline=").append(authzOverrideCount()).append('\n');
            for (Entry entry : entries) {
                if (entry.status() == Status.NON_SPRING) {
                    continue;
                }
                text.append(entry.className()).append('\t')
                        .append(entry.status() == Status.FAILED ? Status.FAILED.name() : SPRING_ROW);
                if (entry.status() == Status.FAILED) {
                    text.append('\t').append(entry.failure());
                } else {
                    entry.authzOverrides().forEach(override -> text.append('\t').append(override));
                }
                text.append('\n');
            }
            return text.toString();
        }
    }

    /** 凍結スナップショット。 */
    public record Snapshot(Map<String, Entry> rows, int distinctKeysBaseline, int authzOverrideBaseline) {

        public static Snapshot parse(String text) {
            Map<String, Entry> rows = new LinkedHashMap<>();
            int distinct = -1;
            int authz = -1;
            for (String raw : text.split("\n")) {
                String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("!distinctKeysBaseline=")) {
                    distinct = Integer.parseInt(line.substring(line.indexOf('=') + 1).trim());
                    continue;
                }
                if (line.startsWith("!authzOverrideBaseline=")) {
                    authz = Integer.parseInt(line.substring(line.indexOf('=') + 1).trim());
                    continue;
                }
                String[] cells = line.split("\t");
                // SPRING は Spring 対象（認可型 override だけを持つ）。解析結果の ZERO・OVERRIDES とは区別せず OVERRIDES で読む。
                Status status = cells[1].equals(SPRING_ROW) ? Status.OVERRIDES : Status.valueOf(cells[1]);
                List<String> overrides = new ArrayList<>();
                String failure = null;
                if (status == Status.FAILED) {
                    failure = cells.length > 2 ? cells[2] : "";
                } else {
                    for (int i = 2; i < cells.length; i++) {
                        overrides.add(cells[i]);
                    }
                }
                rows.put(cells[0], new Entry(cells[0], status, null, List.copyOf(overrides), failure));
            }
            if (distinct < 0 || authz < 0) {
                throw new IllegalStateException("スナップショットに基準値（!distinctKeysBaseline / !authzOverrideBaseline）が無い");
            }
            return new Snapshot(rows, distinct, authz);
        }
    }

    /**
     * スナップショットと実測を照合し、違反（red の理由）を返す。空なら合格。
     *
     * <ul>
     *   <li>認可型 override の追加（クラスごと。新規クラスはスナップショットの集合を空とみなす）</li>
     *   <li>認可型ではない override の増減は照合しない（認可 Bean の mock 化を防ぐのが目的で、無関係な変更に再生成を強いない）</li>
     *   <li>既知の対象の解析失敗・新規の未分類（スナップショットに無い解析失敗）・対象クラスの脱落（AC-3）</li>
     *   <li>distinct key 数の基準値超え（AC-4 のラチェット）</li>
     * </ul>
     */
    public static List<String> violations(Snapshot snapshot, Census census) {
        List<String> result = new ArrayList<>();
        Map<String, Entry> current = census.byName();
        for (Entry entry : census.entries()) {
            Entry frozen = snapshot.rows().get(entry.className());
            Set<String> allowed = frozen == null ? Set.of()
                    : frozen.overrides().stream().filter(ContextOverrideCensus::isAuthzOverride)
                            .collect(Collectors.toCollection(TreeSet::new));
            List<String> added = entry.authzOverrides().stream().filter(override -> !allowed.contains(override))
                    .collect(Collectors.toList());
            if (!added.isEmpty()) {
                result.add("[認可型 override の追加] " + entry.className() + " " + added
                        + "（認可を検査しなくなる。追加は殿の判断事項であり、スナップショットの再生成で通してはならない）");
            }
            if (entry.status() == Status.FAILED) {
                if (frozen == null) {
                    result.add("[新規の未分類] " + entry.className() + " を解析できない: " + entry.failure());
                } else if (frozen.status() != Status.FAILED) {
                    result.add("[既知の対象の解析失敗] " + entry.className() + " を解析できない: " + entry.failure());
                }
            }
        }
        for (Entry frozen : snapshot.rows().values()) {
            if (frozen.status() == Status.FAILED) {
                continue;
            }
            Entry now = current.get(frozen.className());
            if (now == null) {
                result.add("[対象クラスの脱落] " + frozen.className() + " が棚卸しから消えた（削除・改名ならスナップショットを再生成する）");
            } else if (now.status() == Status.NON_SPRING) {
                result.add("[対象クラスの脱落] " + frozen.className() + " が Spring のテストとして解析されなくなった");
            }
        }
        if (census.distinctKeys() > snapshot.distinctKeysBaseline()) {
            result.add("[distinct key 数のラチェット] 実測 " + census.distinctKeys() + " > 基準値 "
                    + snapshot.distinctKeysBaseline() + "（構成キーを増やさない。共通構成へ寄せる）");
        }
        return result;
    }

    /** 実測スナップショットを backend/build 配下へ書き出し、その位置を返す。 */
    public static Path writeActual(String content) throws IOException {
        // build/classes/java/test -> build
        Path build = testClassesRoot().getParent().getParent().getParent();
        Path out = build.resolve(ACTUAL_OUTPUT);
        Files.createDirectories(out.getParent());
        Files.writeString(out, content, StandardCharsets.UTF_8);
        return out;
    }

    /** 述語に合うクラスだけの棚卸し。 */
    public static Census filter(Census census, Predicate<Entry> predicate) {
        return new Census(census.entries().stream().filter(predicate).collect(Collectors.toList()));
    }
}
