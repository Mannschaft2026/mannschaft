package com.mannschaft.app.common.testing;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.springframework.test.context.MergedContextConfiguration;

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
 * <p>試練（red）段階のスタブ。出陣で実装する。</p>
 */
public final class ContextGroupClassOrderer implements ClassOrderer {

    @Override
    public void orderClasses(ClassOrdererContext context) {
        throw new UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）");
    }

    /**
     * 与えられたクラス列を並べ替えた結果を返す（{@link #orderClasses} の本体。入力は変更しない）。
     */
    public static Result order(List<Class<?>> classes) {
        throw new UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）");
    }

    /**
     * テストクラスのコンテキストキー（12桁の16進小文字）。Spring 非使用・MCC 構築例外なら {@code null}。
     */
    public static String contextKey(Class<?> testClass) {
        throw new UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）");
    }

    /** MCC からキーを計算する（testClass は無視する）。Listener もこれを使い、Orderer とキーを一致させる。 */
    public static String contextKey(MergedContextConfiguration configuration) {
        throw new UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）");
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
