package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FiscalIntegrityJobView(
        UUID id,
        FiscalMode mode,
        FiscalIntegrityJobStatus status,
        long billingSnapshotSequence,
        long eventSnapshotSequence,
        long billingChecked,
        long eventsChecked,
        long anomaliesTotal,
        long billingAnomalies,
        long eventAnomalies,
        List<String> evidenceCodes,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt) {
    static FiscalIntegrityJobView from(FiscalIntegrityJob job) {
        return new FiscalIntegrityJobView(job.getId(), job.getExecutionMode(), job.getStatus(),
                job.getBillingSnapshotSequence(), job.getEventSnapshotSequence(),
                job.getBillingChecked(), job.getEventsChecked(), job.getAnomaliesTotal(),
                job.getBillingAnomalies(), job.getEventAnomalies(), job.getEvidenceCodes(),
                job.getError(), job.getCreatedAt(), job.getStartedAt(), job.getUpdatedAt(),
                job.getCompletedAt());
    }
}
