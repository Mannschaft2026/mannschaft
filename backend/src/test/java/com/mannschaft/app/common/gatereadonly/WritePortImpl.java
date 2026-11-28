package com.mannschaft.app.common.gatereadonly;

import com.mannschaft.app.common.gatereadonly.repository.GateFixtureRepository;

/** {@link WritePort} の書き込む実装。 */
public class WritePortImpl implements WritePort {
    private final GateFixtureRepository repository;

    public WritePortImpl(GateFixtureRepository repository) {
        this.repository = repository;
    }

    @Override
    public void write() {
        repository.deleteById(1L);
    }
}
