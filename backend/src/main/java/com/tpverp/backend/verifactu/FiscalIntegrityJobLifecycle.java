package com.tpverp.backend.verifactu;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FiscalIntegrityJobLifecycle {
    private final FiscalIntegrityJobLauncher launcher;
    private final FiscalIntegrityJobService jobs;

    public FiscalIntegrityJobLifecycle(FiscalIntegrityJobLauncher launcher,
            FiscalIntegrityJobService jobs) {
        this.launcher = launcher;
        this.jobs = jobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeOnStartup() {
        launcher.resumeAfterRestart();
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.integrity-job-poll-ms:15000}")
    public void dispatchQueued() {
        var stale = jobs.recoverStaleJobs();
        if (stale != null) stale.forEach(launcher::cancel);
        launcher.launchQueuedBatch();
    }
}
