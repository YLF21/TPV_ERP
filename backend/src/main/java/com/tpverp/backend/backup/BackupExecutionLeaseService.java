package com.tpverp.backend.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable fencing for backup workers. Every method is a short transaction;
 * pg_dump/ZIP/encryption never run while a database transaction is open.
 */
@Service
public class BackupExecutionLeaseService {
    public static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final BackupExecutionRepository executions;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final BackupExecutionLeaseWriter heartbeatWriter;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tpv-backup-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public BackupExecutionLeaseService(BackupExecutionRepository executions, Clock clock,
            ObjectMapper objectMapper, BackupExecutionLeaseWriter heartbeatWriter) {
        this.executions = executions;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.heartbeatWriter = heartbeatWriter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lease claim(BackupSettings configuration) {
        Instant now = Instant.now(clock);
        executions.expireStaleLeases(configuration.getId(), now);
        UUID token = UUID.randomUUID();
        BackupExecution execution = new BackupExecution(configuration, now, token, now,
                now.plus(LEASE_DURATION));
        try {
            executions.saveAndFlush(execution);
            return new Lease(execution.getId(), token);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Ya existe otro worker de backup activo", ex);
        }
    }

    public void heartbeat(Lease lease) {
        Instant now = Instant.now(clock);
        if (!heartbeatWriter.heartbeat(lease.executionId(), lease.workerToken(), now.plus(LEASE_DURATION))) {
            throw new IllegalStateException("El worker de backup perdió su lease");
        }
    }

    /** Keeps a long-running external command fenced without an open DB transaction. */
    public LeaseHeartbeat startHeartbeat(Lease lease) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try { heartbeat(lease); }
            catch (RuntimeException ex) { failure.compareAndSet(null, ex); }
        }, 30, 30, TimeUnit.SECONDS);
        return new LeaseHeartbeat(task, failure);
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    public static final class LeaseHeartbeat implements AutoCloseable {
        private final ScheduledFuture<?> task;
        private final AtomicReference<RuntimeException> failure;
        private LeaseHeartbeat(ScheduledFuture<?> task, AtomicReference<RuntimeException> failure) {
            this.task = task;
            this.failure = failure;
        }
        public void check() {
            RuntimeException ex = failure.get();
            if (ex != null) throw ex;
        }
        @Override public void close() { task.cancel(false); }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Lease lease, Map<String, Object> metadata) {
        finish(lease, BackupResult.EXITO, metadata, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Lease lease, String reason) {
        finish(lease, BackupResult.FALLO, null,
                reason == null || reason.isBlank() ? "BACKUP_FAILED" : reason);
    }

    private void finish(Lease lease, BackupResult result, Map<String, Object> metadata,
            String reason) {
        Instant now = Instant.now(clock);
        String json;
        try {
            json = metadata == null ? null : objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Metadata de backup no serializable", ex);
        }
        if (executions.finishLease(lease.executionId(), lease.workerToken(), result.name(), now,
                json, reason) != 1) {
            throw new IllegalStateException("El worker de backup ya no posee el lease");
        }
    }

    public record Lease(UUID executionId, UUID workerToken) { }
}
