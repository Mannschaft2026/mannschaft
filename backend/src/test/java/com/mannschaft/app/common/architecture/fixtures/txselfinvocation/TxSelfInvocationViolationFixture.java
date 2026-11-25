package com.mannschaft.app.common.architecture.fixtures.txselfinvocation;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 検体（負例）: 非トランザクションのメソッドから、同一クラスの {@code @Transactional} メソッドを
 * {@code this} 経由で呼ぶ（CMP-261009-0010 の {@code AdCampaignDeliveryWorker} の最小再現）。
 *
 * <p>Spring の {@code @Transactional} はプロキシ経由でしか効かないため、{@link #run()} から
 * {@link #loadForUpdate()} を呼んでもトランザクションは始まらない。番人はこの形を必ず検出しなければならない。
 * 本クラスは Spring bean ではない（コンポーネントスキャンに載せない）。</p>
 */
public class TxSelfInvocationViolationFixture {

    /** 負例の呼び出し元（非TX）。 */
    public int run() {
        return loadForUpdate().size();
    }

    /** 負例の呼び出し元（非TX のラムダ内）。ラムダ本体は合成メソッドとして同一クラスに置かれる。 */
    public Runnable runLater() {
        return () -> loadForUpdate();
    }

    @Transactional
    List<String> loadForUpdate() {
        return List.of();
    }
}
