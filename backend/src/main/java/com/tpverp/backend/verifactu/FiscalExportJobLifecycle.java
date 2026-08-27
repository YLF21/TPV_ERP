package com.tpverp.backend.verifactu;

import java.time.Instant;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tpv.verifactu.export-job-single-instance",
        havingValue = "true", matchIfMissing = true)
public class FiscalExportJobLifecycle {
    private final FiscalExportJobLauncher launcher;
    private final FiscalExportJobService jobs;

    public FiscalExportJobLifecycle(FiscalExportJobLauncher launcher,
            FiscalExportJobService jobs) {
        this.launcher = launcher;
        this.jobs = jobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        launcher.resumeAfterRestart();
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.export-job-cleanup-delay-ms:3600000}")
    public void cleanupExpired() {
        jobs.expireJobs(Instant.now());
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.export-job-queue-delay-ms:30000}",
            initialDelayString = "${tpv.verifactu.export-job-queue-initial-delay-ms:30000}")
    public void launchQueuedJobs() {
        launcher.resumeStaleJobs();
        launcher.launchQueuedBatch();
    }
}
