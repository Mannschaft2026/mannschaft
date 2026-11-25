package com.mannschaft.app.common.architecture.fixtures.txselfinvocation;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 検体: 別 bean 相当の協力者。ここへの呼び出しはプロキシを通るので自己呼出しではない。 */
public class TxSelfInvocationCollaborator {

    @Transactional
    public List<String> loadForUpdate() {
        return List.of();
    }
}
