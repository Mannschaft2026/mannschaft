package com.mannschaft.app.common.gatereadonly.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 門の読み取り専用番人の自己検査用 fixture。 */
public interface GateFixtureRepository {
    Object findPlain();

    void save(Object entity);

    void deleteById(Long id);

    @Modifying
    @Query("UPDATE gate_fixture SET touched = 1")
    int bulkTouch();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Object findLocked();

    @Query(value = "SELECT * FROM gate_fixture WHERE id = 1 FOR UPDATE", nativeQuery = true)
    Object findForUpdate();
}
