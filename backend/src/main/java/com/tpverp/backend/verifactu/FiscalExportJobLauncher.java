package com.tpverp.backend.verifactu;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;

@Component
public class FiscalExportJobLauncher {
    private final FiscalExportJobService jobs;
    private final ThreadPoolTaskExecutor executor;
    private final Set<UUID> dispatched = ConcurrentHashMap.newKeySet();

    public FiscalExportJobLauncher(FiscalExportJobService jobs,
            @Qualifier("fiscalExportJobExecutor") ThreadPoolTaskExecutor executor) {
        this.jobs = jobs;
        this.executor = executor;
    }

    public void launch(UUID jobId) {
        if (jobId == null || !dispatched.add(jobId)) return;
        try {
            executor.execute(() -> {
                try { jobs.run(jobId); }
                finally { dispatched.remove(jobId); }
            });
        } catch (RuntimeException rejected) {
            dispatched.remove(jobId);
        }
    }

    public void resumeAfterRestart() {
        resumeStaleJobs();
        launchQueuedBatch();
    }

    public void resumeStaleJobs() {
        jobs.requeueInterruptedJobs();
    }

    public void launchQueuedBatch() {
        var capacity = executor.getMaxPoolSize() + executor.getQueueCapacity()
                - executor.getActiveCount() - executor.getQueueSize();
        if (capacity <= 0) return;
        jobs.queuedIds().stream().limit(Math.min(20, capacity)).forEach(this::launch);
    }
}
