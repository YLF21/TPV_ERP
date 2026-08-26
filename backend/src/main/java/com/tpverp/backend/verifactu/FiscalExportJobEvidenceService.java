package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically records the fiscal export and its NO VERI*FACTU audit event. */
@Service
public class FiscalExportJobEvidenceService {
    private final FiscalEventService events;
    private final FiscalExportRepository exports;
    private final FiscalExportJobRepository jobs;
    private final FiscalRequiredSubmissionRepository submissions;

    public FiscalExportJobEvidenceService(FiscalEventService events, FiscalExportRepository exports,
            FiscalExportJobRepository jobs, FiscalRequiredSubmissionRepository submissions) {
        this.events = events;
        this.exports = exports;
        this.jobs = jobs;
        this.submissions = submissions;
    }

    @Transactional
    public UUID register(FiscalExportJob job, FiscalEventSummary summary,
            FiscalExportContext context, String contentHash, long recordCount) {
        return register(job, summary, context, contentHash, recordCount, Instant.now());
    }

    @Transactional
    public UUID register(FiscalExportJob job, FiscalEventSummary summary,
            FiscalExportContext context, String contentHash, long recordCount,
            Instant exportedAt) {
        UUID eventId = null;
        if (job.getExecutionMode() == FiscalMode.NO_VERIFACTU) {
            var eventType = job.getKind() == FiscalExportKind.EVENTS
                    ? FiscalEventType.EVENT_EXPORT : FiscalEventType.BILLING_EXPORT;
            var event = events.create(job.getCompanyId(), job.getInstallationId(),
                    job.getExecutionMode(), eventType, null, summary, context);
            if (event == null) {
                throw new IllegalStateException("fiscal_export_event_not_recorded");
            }
            eventId = event.getId();
        }
        exports.save(new FiscalExport(job.getId(), job.getCompanyId(), job.getInstallationId(),
                job.getKind(), eventId, recordCount, contentHash, exportedAt,
                job.getPeriodStart(), job.getPeriodEnd()));
        return eventId;
    }

    @Transactional
    public UUID registerEvidenceAndCompleteJob(FiscalExportJob job, FiscalEventSummary summary,
            FiscalExportContext context, String contentHash, long recordCount,
            Instant exportedAt, String filePath, long fileSize, Instant expiresAt) {
        var ownedJob = job;
        if (job.getExecutionToken() != null) {
            ownedJob = jobs.findForUpdateByIdAndExecutionToken(job.getId(), job.getExecutionToken())
                    .orElseThrow(() -> new IllegalStateException("fiscal_export_claim_lost"));
        }
        var eventId = register(ownedJob, summary, context, contentHash, recordCount, exportedAt);
        if (ownedJob.getRequiredSubmissionId() != null) {
            if (submissions == null) throw new IllegalStateException("fiscal_required_submission_unavailable");
            var submission = submissions.findForUpdateByIdAndCompanyIdAndInstallationId(
                    ownedJob.getRequiredSubmissionId(), ownedJob.getCompanyId(), ownedJob.getInstallationId())
                    .orElseThrow(() -> new IllegalStateException("fiscal_required_submission_not_found"));
            if (submission.getPeriodStart() == null || submission.getPeriodEnd() == null
                    || !submission.getPeriodStart().equals(ownedJob.getPeriodStart())
                    || !submission.getPeriodEnd().equals(ownedJob.getPeriodEnd())) {
                throw new IllegalStateException("fiscal_required_submission_period_mismatch");
            }
            submission.markExported(ownedJob.getId(), exportedAt);
            submissions.save(submission);
        }
        ownedJob.markCompleted(filePath, fileSize, recordCount, false, exportedAt, expiresAt);
        jobs.save(ownedJob);
        return eventId;
    }
}
