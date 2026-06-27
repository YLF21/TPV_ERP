package com.tpverp.backend.verifactu;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VerifactuImmediateSubmissionListener {

    private final VerifactuSubmissionWorker worker;
    private final Environment environment;

    public VerifactuImmediateSubmissionListener(
            VerifactuSubmissionWorker worker, Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void submit(FiscalRecordQueuedEvent event) {
        if (environment.getProperty(
                "tpv.verifactu.worker-enabled", Boolean.class, true)) {
            worker.process(event.recordId());
        }
    }
    // Attempts submission outside the transaction and the thread that confirmed the sale.
}
