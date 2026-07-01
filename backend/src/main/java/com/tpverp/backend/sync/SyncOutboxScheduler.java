package com.tpverp.backend.sync;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncOutboxScheduler {

    private final SyncOutboxWorker worker;
    private final Environment environment;

    public SyncOutboxScheduler(SyncOutboxWorker worker, Environment environment) {
        this.worker = worker;
        this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.worker-delay-ms:60000}")
    public void tick() {
        if (enabled()) {
            worker.runOnce();
        }
    }

    private boolean enabled() {
        return environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false);
    }
}
