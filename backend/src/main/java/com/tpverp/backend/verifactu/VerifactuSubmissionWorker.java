package com.tpverp.backend.verifactu;

import org.springframework.stereotype.Service;

@Service
public class VerifactuSubmissionWorker {

    private final FiscalSubmissionQueueService queue;
    private final VerifactuSubmissionService submissions;

    public VerifactuSubmissionWorker(
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionService submissions) {
        this.queue = queue;
        this.submissions = submissions;
    }

    public VerifactuWorkerResult processNext() {
        var batch = queue.claimNextBatch(1000);
        if (batch != null && batch.isPresent()) {
            return VerifactuWorkerResult.from(submissions.submitBatch(batch.get()));
        }
        if (queue.batchCoordinatorAvailable()) {
            return VerifactuWorkerResult.empty();
        }
        // Compatibility path for focused legacy embedders without V226 scope control.
        return queue.claimNext()
                .map(claimed -> VerifactuWorkerResult.from(submit(claimed)))
                .orElseGet(VerifactuWorkerResult::empty);
    }
    // Processes a single record to avoid long batches and keep retries controlled.

    /** Package-private compatibility entry point for the post-commit listener. */
    VerifactuWorkerResult process(java.util.UUID recordId) {
        if (queue.batchCoordinatorAvailable()) {
            return queue.claimBatchForRecord(recordId, 1000)
                    .map(batch -> VerifactuWorkerResult.from(submissions.submitBatch(batch)))
                    .orElseGet(VerifactuWorkerResult::empty);
        }
        return queue.claim(recordId)
                .map(claimed -> VerifactuWorkerResult.from(submit(claimed)))
                .orElseGet(VerifactuWorkerResult::empty);
    }

    public VerifactuWorkerResult processPendingForScope(
            java.util.UUID companyId,
            java.util.UUID installationId,
            java.util.UUID recordId) {
        var batch = queue.claimBatchForRecord(companyId, installationId, recordId, 1000);
        if (batch != null && batch.isPresent()) {
            return VerifactuWorkerResult.from(submissions.submitBatch(batch.get()));
        }
        if (queue.batchCoordinatorAvailable()) return VerifactuWorkerResult.empty();
        return queue.claimPendingForScope(companyId, installationId, recordId)
                .map(claimed -> VerifactuWorkerResult.from(
                        claimed.record().getId(), submit(claimed)))
                .orElseGet(VerifactuWorkerResult::empty);
    }
    // Immediately processes the newly confirmed record if it is still claimable.

    public int processBatch(int batchSize) {
        var batch = queue.claimNextBatch(Math.min(1000, Math.max(1, batchSize)));
        if (batch != null && batch.isPresent()) {
            return submissions.submitBatch(batch.get()).results().size();
        }
        if (queue.batchCoordinatorAvailable()) return 0;
        var result = processNext();
        return result.processed() ? 1 : 0;
    }

    private VerifactuSubmissionResult submit(ClaimedFiscalSubmission claimed) {
        var token = claimed.state().getClaimToken();
        return token == null
                ? submissions.submit(claimed.record())
                : submissions.submit(claimed.record(), token);
    }
}
