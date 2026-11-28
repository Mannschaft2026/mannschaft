package com.mannschaft.app.common.gatereadonly;

import com.mannschaft.app.common.gatereadonly.repository.GateFixtureRepository;
import org.springframework.transaction.annotation.Transactional;

/** 合格すべき門: readOnly の @Transactional と、読み取りだけの推移呼び出し・interface 展開。 */
@Transactional(readOnly = true)
public class CleanGate {
    private final GateFixtureRepository repository;
    private final ReadPort port;

    public CleanGate(GateFixtureRepository repository, ReadPort port) {
        this.repository = repository;
        this.port = port;
    }

    public Object read() {
        port.read();
        return new ReadHelper(repository).read();
    }
}
