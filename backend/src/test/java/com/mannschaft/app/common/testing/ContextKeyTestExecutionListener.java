package com.mannschaft.app.common.testing;

import java.util.List;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * CMP-261111-0508（甲）: Spring テストコンテキストのロード実績を {@code [ci-ctx]} 行で出す TestExecutionListener。
 *
 * <p>契約（受け入れ条件 K-5）:</p>
 * <ul>
 *   <li>spring.factories で全 Spring テストに載せる（順序を明記する）。</li>
 *   <li>行形式: {@code [ci-ctx] jvm=<id> seq=<JVM内通し番号> class=<FQCN> key=<12桁> event=<load|hit|reload|error>
 *       loadMs=<n> cacheSize=<n>}。キーは {@link ContextGroupClassOrderer#contextKey} と同じ。</li>
 *   <li>初回ロード=load、キャッシュヒット=hit、同じキーの再ロード（@DirtiesContext・LRU 追い出し・手動破棄の後）=reload。</li>
 *   <li>計測のための余分な起動・破棄をしない。計測自体の失敗は握って event=error を出し、テストを落とさない。
 *       本来のコンテキスト起動失敗はテスト失敗のまま残す。</li>
 *   <li>秘密値（プロパティ値そのもの）を出さない。</li>
 *   <li>標準出力への出力は system property {@code mannschaft.ci.ctx.log=true}（{@code :test} だけ）のときに限る。</li>
 * </ul>
 *
 * <p>試練（red）段階のスタブ（何もしない）。出陣で実装する。</p>
 */
public class ContextKeyTestExecutionListener extends AbstractTestExecutionListener {

    /**
     * 出力した {@code [ci-ctx]} 行を、標準出力の有効・無効にかかわらず記録し始める（検体テスト用）。
     * 戻り値を close すると記録をやめる。
     */
    public static Capture startCapture() {
        throw new UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）");
    }

    /**
     * 次の計測処理（キー計算等）で指定の例外を投げさせる（計測失敗を握る経路の検体テスト用）。
     * {@code null} で解除。
     */
    public static void injectMeasurementFailure(RuntimeException failure) {
        throw new UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）");
    }

    /** 記録中の {@code [ci-ctx]} 行。 */
    public interface Capture extends AutoCloseable {
        List<String> lines();

        @Override
        void close();
    }
}
