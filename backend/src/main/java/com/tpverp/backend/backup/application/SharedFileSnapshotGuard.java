package com.tpverp.backend.backup.application;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates the local application's file stores with database snapshots.
 * This is a JVM boundary: another process writing the same directories must be stopped externally.
 */
public final class SharedFileSnapshotGuard {

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);

    private SharedFileSnapshotGuard() {}

    public static Scope snapshot() {
        return new Scope(LOCK.writeLock());
    }

    public static Scope fileMutation() {
        return new Scope(LOCK.readLock());
    }

    /** Acquire before reading the entity and retain a hold until its database transaction completes. */
    public static Scope transactionalMutation() {
        Scope operation = fileMutation();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            Scope transaction = fileMutation();
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        transaction.close();
                    }
                });
            } catch (RuntimeException exception) {
                transaction.close();
                operation.close();
                throw exception;
            }
        }
        return operation;
    }

    public static final class Scope implements AutoCloseable {
        private final Lock lock;
        private boolean closed;

        private Scope(Lock lock) {
            this.lock = lock;
            lock.lock();
        }

        @Override
        public void close() {
            if (!closed) {
                lock.unlock();
                closed = true;
            }
        }
    }
}
