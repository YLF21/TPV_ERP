package com.tpverp.backend.supervision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StoreFailureScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(StoreFailureScheduler.class);
    private final StoreFailurePublisher publisher;
    private final Environment environment;
    public StoreFailureScheduler(StoreFailurePublisher publisher, Environment environment) {
        this.publisher = publisher; this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.supervision-delay-ms:60000}",
            initialDelayString = "${tpv.sync.supervision-initial-delay-ms:30000}")
    public void tick() {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)) return;
        try {
            for (var site : publisher.sites()) {
                try { publisher.publish(site); }
                catch (RuntimeException failure) {
                    // No raw exception, customer identifiers or credentials in logs.
                    LOG.warn("STORE_FAILURE_REPORT_PENDING: se reintentara el informe operativo local");
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("STORE_FAILURE_REPORT_UNAVAILABLE: no se pudo consultar el alcance local");
        }
    }
}
