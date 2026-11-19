package com.mannschaft.app.common.testing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * CMP-261111-0508（甲）: Spring テストコンテキストのロード実績を {@code [ci-ctx]} 行で出す TestExecutionListener。
 *
 * <p>契約（受け入れ条件 K-5）:</p>
 * <ul>
 *   <li>spring.factories で全 Spring テストに載せる。順序は {@link #ORDER}（後述）。</li>
 *   <li>行形式: {@code [ci-ctx] jvm=<pid> seq=<JVM内通し番号> class=<FQCN> key=<12桁> event=<load|hit|reload|error>
 *       loadMs=<n> cacheSize=<n>}。キーは {@link ContextGroupClassOrderer#contextKey} と同じ。</li>
 *   <li>初回ロード=load、キャッシュヒット=hit（クラスにつき1回だけ出す）、同じキーの再ロード
 *       （@DirtiesContext・LRU 追い出し・手動破棄の後）=reload。</li>
 *   <li>計測のための余分な起動・破棄をしない（観測だけ。context のロードは従来どおり DependencyInjection リスナーが行う）。
 *       計測自体の失敗は握って event=error を出し、テストを落とさない。本来の起動失敗はテスト失敗のまま残る。</li>
 *   <li>秘密値（プロパティ値そのもの）を出さない。キー（ハッシュ）・クラス名・数値・例外クラス名だけを出す。</li>
 *   <li>標準出力への出力は system property {@value #LOG_PROPERTY}{@code =true}（{@code :test} だけ。build-logic の
 *       ContextOrdererSettings が渡す）のときに限る。archTest・archUnitTest 等では出さない。</li>
 * </ul>
 *
 * <h2>reload の判定方式</h2>
 * <p>Spring の ContextCache 統計（hit/miss）は版依存の内部状態なので判定には使わない。
 * 「ロード前にキャッシュへ入っていたか」は公開 API の {@link TestContext#hasApplicationContext()} で取り、
 * ロードが起きたときは JVM 内で既にロードしたことのあるキーの集合と照合して、あれば reload、なければ load とする。
 * ContextCache の件数（cacheSize）だけは参考値としてリフレクションで読み、読めなければ -1 にする。</p>
 *
 * <h2>順序</h2>
 * <p>{@link #ORDER}=1900。DirtiesContextBeforeModesTestExecutionListener（1500）の後ろ（BEFORE_CLASS で破棄された後の
 * 状態を見るため）、DependencyInjectionTestExecutionListener（2000。ここで実際にロードされる）の前。
 * ロード前の状態を prepareTestInstance / beforeTestClass で記録し、ロード後の beforeTestMethod で結果を確定する
 * （beforeTestMethod の BEFORE_METHOD 破棄の影響を受けないよう、確定時にキャッシュを再照会しない）。</p>
 */
public class ContextKeyTestExecutionListener extends AbstractTestExecutionListener {

    /** DirtiesContext(BEFORE_*)（1500）より後・DependencyInjection（2000）より前。 */
    public static final int ORDER = 1900;

    /** 標準出力への {@code [ci-ctx]} 出力を有効にする system property（{@code :test} だけ true）。 */
    public static final String LOG_PROPERTY = "mannschaft.ci.ctx.log";

    private static final String NO_KEY = "000000000000";

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Set<String> LOADED_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> HIT_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, Pending> PENDING = new ConcurrentHashMap<>();
    /** クラスごとのキー（メソッドごとに MCC を作り直さないための控え。afterTestClass で捨てる）。 */
    private static final Map<Class<?>, String> KEYS = new ConcurrentHashMap<>();
    private static final List<List<String>> CAPTURES = new CopyOnWriteArrayList<>();
    private static volatile RuntimeException injectedFailure;
    private static volatile Field delegateField;
    private static volatile Field cacheField;
    private static volatile boolean cacheSizeUnavailable;

    /** ロード前の状態。{@code armedByClass} は beforeTestClass で作り、最初の prepareTestInstance で上書きしない印。 */
    private record Pending(String key, boolean wasLoaded, long startNanos, boolean armedByClass) {
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        begin(testContext, true);
    }

    @Override
    public void prepareTestInstance(TestContext testContext) {
        Pending armed = PENDING.get(testContext.getTestClass());
        if (armed != null && armed.armedByClass()) {
            // beforeTestClass で記録済み（コンストラクタ注入でここより前にロードされていても、その前の状態を採る）。
            PENDING.put(testContext.getTestClass(),
                    new Pending(armed.key(), armed.wasLoaded(), armed.startNanos(), false));
            return;
        }
        begin(testContext, false);
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        Pending pending = PENDING.remove(testContext.getTestClass());
        if (pending == null) {
            return;
        }
        try {
            if (pending.wasLoaded()) {
                if (HIT_LOGGED.add(testContext.getTestClass())) {
                    emit(testContext, pending.key(), "hit", 0L, "");
                }
                return;
            }
            long loadMs = (System.nanoTime() - pending.startNanos()) / 1_000_000L;
            boolean reload = !LOADED_KEYS.add(pending.key());
            emit(testContext, pending.key(), reload ? "reload" : "load", loadMs, "");
        } catch (Throwable failure) { // NOSONAR 計測の失敗でテストを落とさない。
            emitError(testContext, failure);
        }
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        PENDING.remove(testContext.getTestClass());
        HIT_LOGGED.remove(testContext.getTestClass());
        KEYS.remove(testContext.getTestClass());
    }

    private static void begin(TestContext testContext, boolean armedByClass) {
        try {
            RuntimeException injected = injectedFailure;
            if (injected != null) {
                throw injected;
            }
            String key = KEYS.get(testContext.getTestClass());
            if (key == null) {
                key = ContextGroupClassOrderer.contextKey(testContext.getTestClass());
                if (key == null) {
                    throw new IllegalStateException("context key unavailable");
                }
                KEYS.put(testContext.getTestClass(), key);
            }
            PENDING.put(testContext.getTestClass(),
                    new Pending(key, testContext.hasApplicationContext(), System.nanoTime(), armedByClass));
        } catch (Throwable failure) { // NOSONAR 計測の失敗でテストを落とさない。
            PENDING.remove(testContext.getTestClass());
            emitError(testContext, failure);
        }
    }

    private static void emitError(TestContext testContext, Throwable failure) {
        try {
            emit(testContext, NO_KEY, "error", 0L, " error=" + failure.getClass().getSimpleName());
        } catch (Throwable ignored) { // NOSONAR 計測ログの出力失敗も握る。
            // 何もしない
        }
    }

    private static void emit(TestContext testContext, String key, String event, long loadMs, String suffix) {
        String line = "[ci-ctx] jvm=" + ProcessHandle.current().pid()
                + " seq=" + SEQUENCE.incrementAndGet()
                + " class=" + testContext.getTestClass().getName()
                + " key=" + key
                + " event=" + event
                + " loadMs=" + loadMs
                + " cacheSize=" + cacheSize(testContext)
                + suffix;
        if (Boolean.getBoolean(LOG_PROPERTY)) {
            System.out.println(line);
        }
        for (List<String> capture : CAPTURES) {
            capture.add(line);
        }
    }

    /**
     * ContextCache の件数（参考値）。公開 API が無いため DefaultTestContext → delegate → contextCache を
     * リフレクションで辿る。Spring の版が変わって辿れなくなったら以後は -1 を返す（判定には使わない）。
     */
    private static int cacheSize(TestContext testContext) {
        if (cacheSizeUnavailable) {
            return -1;
        }
        try {
            Field delegate = delegateField;
            if (delegate == null) {
                delegate = findField(testContext.getClass(), "cacheAwareContextLoaderDelegate");
                delegateField = delegate;
            }
            Object loaderDelegate = delegate.get(testContext);
            Field cache = cacheField;
            if (cache == null) {
                cache = findField(loaderDelegate.getClass(), "contextCache");
                cacheField = cache;
            }
            Object contextCache = cache.get(loaderDelegate);
            return (int) contextCache.getClass().getMethod("size").invoke(contextCache);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            cacheSizeUnavailable = true;
            return -1;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException next) {
                // 親クラスを探す
            }
        }
        throw new NoSuchFieldException(name);
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
