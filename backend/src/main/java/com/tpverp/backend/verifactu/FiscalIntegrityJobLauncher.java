package com.tpverp.backend.verifactu;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class FiscalIntegrityJobLauncher {
    private final FiscalIntegrityJobService jobs;
    private final ThreadPoolTaskExecutor executor;
    private final Set<UUID> dispatched = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, FutureTask<Void>> workers = new ConcurrentHashMap<>();

    public FiscalIntegrityJobLauncher(FiscalIntegrityJobService jobs,
            @Qualifier("fiscalIntegrityJobExecutor") ThreadPoolTaskExecutor executor) {
        this.jobs = jobs;
        this.executor = executor;
    }

    public void launch(UUID id) {
        if (id == null || !dispatched.add(id)) return;
        var task = new FutureTask<Void>(() -> {
            try {
                jobs.run(id);
            } finally {
                workers.remove(id);
                dispatched.remove(id);
            }
            return null;
        });
        workers.put(id, task);
        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            workers.remove(id, task);
            dispatched.remove(id);
        }
    }

    /** Interrupts only the local worker; database ownership is revoked first. */
    public void cancel(UUID id) {
        if (id == null) return;
        var task = workers.remove(id);
        if (task != null) {
            task.cancel(true);
        }
        dispatched.remove(id);
    }

    public void resumeAfterRestart() {
        // A RUNNING job can belong to another healthy application instance.
        // Only the lease timeout in the scheduled recovery may requeue it.
        launchQueuedBatch();
    }

    public void launchQueuedBatch() {
        int capacity = executor.getMaxPoolSize() + executor.getQueueCapacity()
                - executor.getActiveCount() - executor.getQueueSize();
        if (capacity <= 0) return;
        jobs.queuedIds().stream().limit(Math.min(10, capacity)).forEach(this::launch);
    }
}
