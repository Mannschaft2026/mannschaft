package com.mannschaft.app.common.architecture.fixtures.txselfinvocation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

/**
 * 検体（正例）: {@code ObjectProvider<Self>} 経由の {@code self().m()}（正規の自己プロキシ取得）。
 *
 * <p>バイトコード上は {@code this.m()} と区別できないため、番人は {@code ObjectProvider<Self>} /
 * {@code Provider<Self>} 型フィールドを持つクラスを根治済みとみなして除外する。</p>
 */
public class TxSelfInvocationObjectProviderFixture {

    private ObjectProvider<TxSelfInvocationObjectProviderFixture> selfProvider;

    public int run() {
        return selfProvider.getObject().inner();
    }

    @Transactional
    int inner() {
        return 0;
    }
}
