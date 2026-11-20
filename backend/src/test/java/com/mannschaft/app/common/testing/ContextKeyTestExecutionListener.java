package com.mannschaft.app.common.testing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * CMP-261111-0508（甲）: Spring テストコンテキストのロード実績を {@code [ci-ctx]} 行で出す計測の本体と、hit の記録リスナー。
 *
 * <p>契約（受け入れ条件 K-5）:</p>
 * <ul>
 *   <li>行形式: {@code [ci-ctx] jvm=<pid> seq=<JVM内通し番号> class=<FQCN> key=<12桁> event=<load|hit|reload|error>
 *       loadMs=<n> cacheSize=<n>}。キーは {@link ContextGroupClassOrderer#contextKey} と同じ。</li>
 *   <li>観測専用。余分な起動・破棄をしない。計測自体の失敗は握って event=error を出し、テストを落とさない。
 *       本来のコンテキスト起動失敗はテスト失敗のまま残る。</li>
 *   <li>秘密値（プロパティ値そのもの）を出さない。キー（ハッシュ）・クラス名・数値・例外クラス名だけを出す。</li>
 *   <li>標準出力への出力は system property {@value #LOG_PROPERTY}{@code =true}（{@code :test} だけ）のときに限る。</li>
 * </ul>
 *
 * <h2>観測方式（リスナーの順序に頼らない）</h2>
 * <p>load / reload は、実際のロード地点で観測する。公式の差し替え口である {@link org.springframework.test.context.ContextCustomizerFactory}
 * （{@link ContextLoadObservingCustomizer}）が、ローダーによるコンテキスト構築中（refresh の直前）に開始時刻を取り、
 * {@code ContextRefreshedEvent} で確定する。呼び出し元（ServletTestExecutionListener・DI リスナー・コンストラクタ注入・
 * PER_CLASS / PER_METHOD・@DirtiesContext の各モード）に関わらず、ロードが起きたときだけ、ロードしたクラス
 * （{@code mcc.getTestClass()}）の行が出る。reload はその JVM で既にロードしたことのあるキーの再ロード
 * （@DirtiesContext・LRU 追い出し・手動破棄の後）。{@code ContextClosedEvent} で生存数を減らし、cacheSize（本計測が
 * 把握している生存コンテキスト数）を出す。ContextCache の内部（非公開フィールド）は読まない。</p>
 * <p>注意: Spring 6.2 の BootstrapUtils は CacheAwareContextLoaderDelegate の差し替え用 SpringProperties キーを持たない
 * （クラス名が固定）ため、委譲クラスの継承ではなく ContextCustomizer を使う。loadMs は customizer 適用（refresh 直前）から
 * refresh 完了までで、コンテキスト生成と Bean 定義登録の手前は含まない。</p>
 *
 * <p>本リスナー（spring.factories 登録・{@link #ORDER}=2100）は hit の記録だけを担う。2100 は DI リスナー（2000）の後なので、
 * ロード・再注入による再ロードは済んでいる。クラスの最初の beforeTestMethod で、そのクラス名の load/reload が
 * 1 件も無く、かつコンテキストが生きていれば、キャッシュヒットとして 1 行（クラスにつき 1 回）出す。</p>
 */
public class ContextKeyTestExecutionListener extends AbstractTestExecutionListener {

    /** DependencyInjectionTestExecutionListener（2000）の後。 */
    public static final int ORDER = 2100;

    /** 標準出力への {@code [ci-ctx]} 出力を有効にする system property（{@code :test} だけ true）。 */
    public static final String LOG_PROPERTY = "mannschaft.ci.ctx.log";

    private static final String NO_KEY = "000000000000";

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicInteger LIVE = new AtomicInteger();
    private static final Set<String> LOADED_KEYS = ConcurrentHashMap.newKeySet();
    /** クラス名 -> そのクラスが引き起こした load/reload の件数（hit 判定用。最初の beforeTestMethod で消費する）。 */
    private static final Map<String, Integer> LOADS_BY_CLASS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> HIT_CHECKED = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, String> KEYS = new ConcurrentHashMap<>();
    private static final List<List<String>> CAPTURES = new CopyOnWriteArrayList<>();
    private static volatile RuntimeException injectedFailure;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        if (!HIT_CHECKED.add(testClass)) {
            return;
        }
        try {
            checkInjectedFailure();
            Integer loads = LOADS_BY_CLASS.remove(testClass.getName());
            if (loads == null && testContext.hasApplicationContext()) {
                String key = KEYS.computeIfAbsent(testClass, type -> {
                    String computed = ContextGroupClassOrderer.contextKey(type);
                    if (computed == null) {
                        throw new IllegalStateException("context key unavailable");
                    }
                    return computed;
                });
                emit(testClass.getName(), key, "hit", 0L, "");
            }
        } catch (Throwable failure) { // NOSONAR 計測の失敗でテストを落とさない。
            emitError(testClass.getName(), failure);
        }
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        Class<?> testClass = testContext.getTestClass();
        HIT_CHECKED.remove(testClass);
        LOADS_BY_CLASS.remove(testClass.getName());
        KEYS.remove(testClass);
    }

    /** ローダーがコンテキストを構築する途中（ContextCustomizer 適用時）に呼ばれ、ロードの観測を仕掛ける。 */
    static void observeLoad(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
        String className = configuration.getTestClass().getName();
        try {
            checkInjectedFailure();
            String key = ContextGroupClassOrderer.contextKey(configuration);
            long start = System.nanoTime();
            boolean[] refreshed = {false};
            context.addApplicationListener((ApplicationListener<ApplicationEvent>) event -> {
                try {
                    if (event instanceof ContextRefreshedEvent refreshedEvent && refreshedEvent.getApplicationContext() == context
                            && !refreshed[0]) {
                        refreshed[0] = true;
                        LIVE.incrementAndGet();
                        // 1ms 未満でも 0 にしない（切り上げ）。
                        long loadMs = (System.nanoTime() - start + 999_999L) / 1_000_000L;
                        boolean reload = !LOADED_KEYS.add(key);
                        LOADS_BY_CLASS.merge(className, 1, Integer::sum);
                        emit(className, key, reload ? "reload" : "load", loadMs, "");
                    } else if (event instanceof ContextClosedEvent closedEvent && closedEvent.getApplicationContext() == context
                            && refreshed[0]) {
                        refreshed[0] = false;
                        LIVE.decrementAndGet();
                    }
                } catch (Throwable failure) { // NOSONAR 計測の失敗でテストを落とさない。
                    emitError(className, failure);
                }
            });
        } catch (Throwable failure) { // NOSONAR 計測の失敗でテストを落とさない。
            emitError(className, failure);
        }
    }

    private static void checkInjectedFailure() {
        RuntimeException injected = injectedFailure;
        if (injected != null) {
            throw injected;
        }
    }

    private static void emitError(String className, Throwable failure) {
        try {
            emit(className, NO_KEY, "error", 0L, " error=" + failure.getClass().getSimpleName());
        } catch (Throwable ignored) { // NOSONAR 計測ログの出力失敗も握る。
            // 何もしない
        }
    }

    private static void emit(String className, String key, String event, long loadMs, String suffix) {
        String line = "[ci-ctx] jvm=" + ProcessHandle.current().pid()
                + " seq=" + SEQUENCE.incrementAndGet()
                + " class=" + className
                + " key=" + key
                + " event=" + event
                + " loadMs=" + loadMs
                + " cacheSize=" + LIVE.get()
                + suffix;
        if (Boolean.getBoolean(LOG_PROPERTY)) {
            System.out.println(line);
        }
        for (List<String> capture : CAPTURES) {
            capture.add(line);
        }
    }

    /**
     * 出力した {@code [ci-ctx]} 行を、標準出力の有効・無効にかかわらず記録し始める（検体テスト用）。
     * 戻り値を close すると記録をやめる。
     */
    public static Capture startCapture() {
        List<String> lines = new CopyOnWriteArrayList<>();
        CAPTURES.add(lines);
        return new Capture() {
            @Override
            public List<String> lines() {
                return List.copyOf(lines);
            }

            @Override
            public void close() {
                CAPTURES.remove(lines);
            }
        };
    }

    /**
     * 次の計測処理（キー計算等）で指定の例外を投げさせる（計測失敗を握る経路の検体テスト用）。
     * {@code null} で解除。
     */
    public static void injectMeasurementFailure(RuntimeException failure) {
        injectedFailure = failure;
    }

    /** 記録中の {@code [ci-ctx]} 行。 */
    public interface Capture extends AutoCloseable {
        List<String> lines();

        @Override
        void close();
    }
}
