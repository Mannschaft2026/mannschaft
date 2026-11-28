package com.mannschaft.app.common.architecture.fixtures.txselfinvocation;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

/**
 * 検体（正例）: 番人が検出してはならない形（偽陽性ゼロの担保）。
 *
 * <ul>
 *   <li>呼び出し元自身が {@code @Transactional}（既にトランザクション内にいる）</li>
 *   <li>{@code @Transactional} メソッド内のラムダからの呼び出し（外側のトランザクション内で実行される前提）</li>
 *   <li>別インスタンス（別 bean 相当）の {@code @Transactional} メソッド呼び出し</li>
 *   <li>同一クラスでも {@code @Transactional} でないメソッドの呼び出し</li>
 * </ul>
 * 本クラスは Spring bean ではない（コンポーネントスキャンに載せない）。
 */
public class TxSelfInvocationAllowedFixture {

    private final TxSelfInvocationCollaborator collaborator = new TxSelfInvocationCollaborator();

    @Transactional
    public int runInTransaction() {
        return load().size();
    }

    @Transactional
    public Supplier<Integer> runInTransactionWithLambda() {
        return () -> load().size();
    }

    public int runViaOtherBean() {
        return collaborator.loadForUpdate().size() + plainHelper();
    }

    @Transactional
    List<String> load() {
        return List.of();
    }

    int plainHelper() {
        return 0;
    }
}
