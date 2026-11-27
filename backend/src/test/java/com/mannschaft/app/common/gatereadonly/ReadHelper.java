package com.mannschaft.app.common.gatereadonly;

import com.mannschaft.app.common.gatereadonly.repository.GateFixtureRepository;

/** 読み取りだけの中継 fixture。 */
public class ReadHelper {
    private final GateFixtureRepository repository;

    public ReadHelper(GateFixtureRepository repository) {
        this.repository = repository;
    }

    public Object read() {
        return repository.findPlain();
    }
}
