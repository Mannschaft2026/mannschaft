package com.mannschaft.app.common.architecture.fixtures.txselfinvocation;

import org.springframework.transaction.annotation.Transactional;

/**
 * 検体（正例）: 自分自身の型のフィールド（{@code @Lazy} 自己注入したプロキシ相当）経由の呼び出し。
 *
 * <p>バイトコード上の呼び出し先は {@code this.m()} と同じく自クラスだが、受け手はプロキシなので TX は効く。
 * ArchUnit は受け手を区別できないため、番人は「自型のフィールドを持つクラス」を自己注入で根治済みとみなして除外する。</p>
 */
public class TxSelfInvocationSelfProxyFixture {

    private TxSelfInvocationSelfProxyFixture self;

    public int run() {
        return self.inner();
    }

    @Transactional
    int inner() {
        return 0;
    }
}
