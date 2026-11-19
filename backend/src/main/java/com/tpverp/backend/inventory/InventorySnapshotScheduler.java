package com.tpverp.backend.inventory;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventorySnapshotScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(InventorySnapshotScheduler.class);

    private final InventorySnapshotCapture capture;
    private final Environment environment;
    private final Duration cadence;

    public InventorySnapshotScheduler(InventorySnapshotCapture capture, Environment environment,
            @Value("${tpv.sync.inventory-snapshot.cadence-ms:3600000}") long cadenceMillis) {
        if (cadenceMillis <= 0) {
            throw new IllegalArgumentException("La cadencia de la captura de existencias debe ser positiva");
        }
        this.capture = capture;
        this.environment = environment;
        this.cadence = Duration.ofMillis(cadenceMillis);
    }

    @Scheduled(fixedDelayString = "${tpv.sync.inventory-snapshot.check-delay-ms:60000}",
            initialDelayString = "${tpv.sync.inventory-snapshot.initial-delay-ms:30000}")
    public void tick() {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)
                || !environment.getProperty("tpv.sync.inventory-snapshot.enabled", Boolean.class, true)
                || environment.getProperty("tpv.sync.central-url", "").isBlank()) {
            return;
        }

        for (var site : capture.sites()) {
            try {
                capture.enqueue(site, cadence);
            } catch (RuntimeException failure) {
                LOG.warn("Se reintentará la captura de existencias para la tienda {} ({})",
                        site.storeId(), failure.getClass().getSimpleName());
            }
        }
    }
}
