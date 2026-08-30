package com.tpverp.backend.backup;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate Spring proxy used by the heartbeat scheduler for REQUIRES_NEW. */
@Service
public class BackupExecutionLeaseWriter {
    private final BackupExecutionRepository executions;
    private final Clock clock;

    public BackupExecutionLeaseWriter(BackupExecutionRepository executions, Clock clock) {
        this.executions = executions;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean heartbeat(UUID executionId, UUID token, Instant leaseUntil) {
        Instant now = Instant.now(clock);
        return executions.heartbeat(executionId, token, now, leaseUntil) == 1;
    }
}
