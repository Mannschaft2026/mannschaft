package com.mannschaft.app.common.architecture.fixtures.txselfinvocation;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 検体（正例）: クラスレベル {@code @Transactional} を持つクラス内の自己呼出し。
 * 呼び出し元が既にトランザクション内にいるため、本番人の検出対象（「TX が付かない」欠陥）には当たらない。
 */
@Transactional
public class TxSelfInvocationClassLevelFixture {

    public int run() {
        return inner();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    int inner() {
        return 0;
    }
}
