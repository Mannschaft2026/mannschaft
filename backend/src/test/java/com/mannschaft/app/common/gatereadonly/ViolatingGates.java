package com.mannschaft.app.common.gatereadonly;

import com.mannschaft.app.common.gatereadonly.repository.GateFixtureRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;

/** 門の読み取り専用番人の自己検査用: 違反するダミーの門。 */
public final class ViolatingGates {
    private ViolatingGates() {
    }

    /** クラスに readOnly でない @Transactional。 */
    @Transactional
    public static class WriteTransactionalClassGate {
        public void read() {
        }
    }

    /** メソッドに readOnly = false の @Transactional。 */
    public static class WriteTransactionalMethodGate {
        @Transactional(readOnly = false)
        public void read() {
        }
    }

    /** 中継を経由して Repository#save へ推移的に到達する。 */
    public static class SaveThroughHelperGate {
        private final WriteHelper helper;

        public SaveThroughHelperGate(WriteHelper helper) {
            this.helper = helper;
        }

        public void read() {
            helper.write();
        }
    }

    /** interface の実装を経由して Repository#delete* へ到達する。 */
    public static class DeleteThroughPortGate {
        private final WritePort port;

        public DeleteThroughPortGate(WritePort port) {
            this.port = port;
        }

        public void read() {
            port.write();
        }
    }

    /** @Modifying の付いた Repository メソッドを呼ぶ。 */
    public static class ModifyingGate {
        private final GateFixtureRepository repository;

        public ModifyingGate(GateFixtureRepository repository) {
            this.repository = repository;
        }

        public int read() {
            return repository.bulkTouch();
        }
    }

    /** @Lock の付いた Repository メソッドを呼ぶ。 */
    public static class LockAnnotationGate {
        private final GateFixtureRepository repository;

        public LockAnnotationGate(GateFixtureRepository repository) {
            this.repository = repository;
        }

        public Object read() {
            return repository.findLocked();
        }
    }

    /** FOR UPDATE の @Query を呼ぶ。 */
    public static class ForUpdateQueryGate {
        private final GateFixtureRepository repository;

        public ForUpdateQueryGate(GateFixtureRepository repository) {
            this.repository = repository;
        }

        public Object read() {
            return repository.findForUpdate();
        }
    }

    /** EntityManager で悲観ロックを取る。 */
    public static class EntityManagerLockGate {
        private final EntityManager entityManager;

        public EntityManagerLockGate(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        public Object read() {
            return entityManager.find(Object.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        }
    }

    /** EntityManager で書き込む。 */
    public static class EntityManagerWriteGate {
        private final EntityManager entityManager;

        public EntityManagerWriteGate(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        public void read() {
            entityManager.persist(new Object());
        }
    }
}
