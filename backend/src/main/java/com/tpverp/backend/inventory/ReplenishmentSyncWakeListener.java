package com.tpverp.backend.inventory;

import com.tpverp.backend.servertransfer.ServerTransferFence;
import com.tpverp.backend.sync.SyncOutboxWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReplenishmentSyncWakeListener {
    private static final Logger LOG = LoggerFactory.getLogger(ReplenishmentSyncWakeListener.class);
    private final SyncOutboxWorker worker;
    private final Environment environment;
    private final ServerTransferFence fence;

    public ReplenishmentSyncWakeListener(SyncOutboxWorker worker, Environment environment, ServerTransferFence fence) {
        this.worker = worker; this.environment = environment; this.fence = fence;
    }

    @Async("replenishmentSyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(ReplenishmentSyncRequested request) {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false) || !fence.enter()) return;
        try {
            worker.runEvent(request.eventId());
        } catch (RuntimeException failure) {
            LOG.warn("Reposicion pendiente de sincronizacion; se reintentara desde outbox", failure);
        } finally {
            fence.leave();
        }
    }
}
