package com.mannschaft.app.common.gatereadonly;

import com.mannschaft.app.common.gatereadonly.repository.GateFixtureRepository;

/** 書き込む中継 fixture（推移到達の自己検査）。 */
public class WriteHelper {
    private final GateFixtureRepository repository;

    public WriteHelper(GateFixtureRepository repository) {
        this.repository = repository;
    }

    public void write() {
        repository.save(new Object());
    }
}
