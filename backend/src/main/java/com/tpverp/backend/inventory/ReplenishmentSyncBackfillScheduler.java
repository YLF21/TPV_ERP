package com.tpverp.backend.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReplenishmentSyncBackfillScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ReplenishmentSyncBackfillScheduler.class);
    private final ReplenishmentSyncPublisher publisher;
    private final Environment environment;

    public ReplenishmentSyncBackfillScheduler(ReplenishmentSyncPublisher publisher, Environment environment) {
        this.publisher = publisher; this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.replenishment.sync-backfill-delay-ms:5000}",
            initialDelayString = "${tpv.replenishment.sync-backfill-initial-delay-ms:10000}")
    public void tick() {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)) return;
        try { publisher.backfill(100); }
        catch (RuntimeException failure) { LOG.warn("Carga inicial de reposicion pendiente de reintento", failure); }
    }
}
