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

    @Scheduled(fixedDelayString = "${tpv.verifactu.worker-delay-ms:3600000}")
    public void tick() {
        if (enabled()) {
            worker.processNext();
        }
    }
    // Ejecuta un unico envio por tick para no bloquear el sistema ni las ventas.

    private boolean enabled() {
        return environment.getProperty(
                "tpv.verifactu.worker-enabled", Boolean.class, true);
    }
}
