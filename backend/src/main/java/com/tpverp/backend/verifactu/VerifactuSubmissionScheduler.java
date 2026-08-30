package com.tpverp.backend.verifactu;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VerifactuSubmissionScheduler {

    private final VerifactuSubmissionWorker worker;
    private final Environment environment;

    public VerifactuSubmissionScheduler(
            VerifactuSubmissionWorker worker,
            Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.worker-delay-ms:60000}")
    public void tick() {
        if (enabled()) {
            // The scope coordinator decides the batch size. A scheduler-side
            // batch size would make the pacing bypass depend on configuration
            // instead of the actual number sent to AEAT.
            worker.processNext();
        }
    }
    // Drena un lote acotado por tick y deja el claim durable si el proceso cae.

    private boolean enabled() {
        return environment.getProperty(
                "tpv.verifactu.worker-enabled", Boolean.class, false);
    }
}
