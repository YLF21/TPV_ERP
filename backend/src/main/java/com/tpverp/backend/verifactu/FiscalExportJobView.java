package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record FiscalExportJobView(
        UUID id,
        FiscalExportKind kind,
        FiscalExportJobScope scope,
        UUID requiredSubmissionId,
        FiscalExportJobStatus status,
        long processed,
        boolean hasMore,
        String error,
        long fileSize,
        boolean downloadAvailable,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant expiresAt) {

    static FiscalExportJobView from(FiscalExportJob job, boolean downloadAvailable,
            FiscalExportJobScope scope) {
        return new FiscalExportJobView(job.getId(), job.getKind(), scope, job.getRequiredSubmissionId(), job.getStatus(), job.getProcessed(),
                job.isHasMore(), job.getError(), job.getFileSize(), downloadAvailable,
                job.getCreatedAt(), job.getStartedAt(), job.getCompletedAt(), job.getExpiresAt());
    }
}
