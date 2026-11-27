package com.mannschaft.app.common.gatereadonly;

import com.mannschaft.app.common.gatereadonly.repository.GateFixtureRepository;

/** {@link ReadPort} の読み取りだけの実装。 */
public class ReadPortImpl implements ReadPort {
    private final GateFixtureRepository repository;

    public ReadPortImpl(GateFixtureRepository repository) {
        this.repository = repository;
    }

    @Override
    public Object read() {
        return repository.findPlain();
    }
}
