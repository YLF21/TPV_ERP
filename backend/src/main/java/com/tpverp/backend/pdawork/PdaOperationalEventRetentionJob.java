package com.tpverp.backend.pdawork;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PdaOperationalEventRetentionJob {
    private final PdaOperationalEventRepository events;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;

    PdaOperationalEventRetentionJob(PdaOperationalEventRepository events, Clock clock,
            @Value("${tpv.pda.observability.retention-days:90}") int retentionDays,
            @Value("${tpv.pda.observability.retention-batch-size:10000}") int batchSize) {
        this.events = events;
        this.clock = clock;
        this.retentionDays = Math.max(8, retentionDays);
        this.batchSize = Math.max(100, Math.min(50000, batchSize));
    }

    @Scheduled(fixedDelayString = "${tpv.pda.observability.retention-delay-ms:3600000}",
            initialDelayString = "${tpv.pda.observability.retention-initial-delay-ms:300000}")
    @Transactional
    public int purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        return events.deleteRetentionBatch(cutoff, batchSize);
    }
}
