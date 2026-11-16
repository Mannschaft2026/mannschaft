package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link ShiftBudgetCloseEventRecorder} の実装。Spring Data が {@code ShiftBudgetAllocationRepository} の断片として
 * クラス名規約（断片名 + {@code Impl}）で結び付ける。
 */
public class ShiftBudgetCloseEventRecorderImpl implements ShiftBudgetCloseEventRecorder {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public ShiftBudgetMonthlyCloseEventEntity recordClose(ShiftBudgetMonthlyCloseEventEntity event) {
        entityManager.persist(event);
        return event;
    }
}
