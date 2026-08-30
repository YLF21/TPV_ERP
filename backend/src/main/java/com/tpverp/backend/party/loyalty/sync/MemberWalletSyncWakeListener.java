package com.tpverp.backend.party.loyalty.sync;

import com.tpverp.backend.sync.SyncOutboxWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MemberWalletSyncWakeListener {

    private static final Logger log = LoggerFactory.getLogger(MemberWalletSyncWakeListener.class);

    private final SyncOutboxWorker worker;
    private final Environment environment;

    public MemberWalletSyncWakeListener(SyncOutboxWorker worker, Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Async("memberWalletSyncWakeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(MemberWalletLotSyncRequested request) {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)) {
            return;
        }
        try {
            worker.runEvent(request.eventId());
        } catch (RuntimeException exception) {
            // El scheduler y el estado durable del outbox siguen siendo el fallback.
            log.warn("No se pudo despertar la sincronizacion del lote {}", request.eventId(), exception);
        }
    }

    /**
     * A return-credit lot is locally usable as soon as its transaction commits.
     * Give its central projection a best-effort, directed attempt before the
     * caller receives the completed-return response; the outbox worker remains
     * the retry path and a remote failure never rolls back the return.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReturnCreditCommitted(MemberReturnCreditSyncRequested request) {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)) {
            return;
        }
        try {
            worker.runEvent(request.eventId());
        } catch (RuntimeException exception) {
            log.warn("No se pudo sincronizar prioritariamente el saldo de devolucion {}",
                    request.eventId(), exception);
        }
    }
}
