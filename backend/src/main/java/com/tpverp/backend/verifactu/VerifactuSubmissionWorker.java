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
        return queue.claimNext()
                .map(claimed -> VerifactuWorkerResult.from(
                        submissions.submit(claimed.record())))
                .orElseGet(VerifactuWorkerResult::empty);
    }
    // Processes a single record to avoid long batches and keep retries controlled.

    public VerifactuWorkerResult process(java.util.UUID recordId) {
        return queue.claim(recordId)
                .map(claimed -> VerifactuWorkerResult.from(
                        submissions.submit(claimed.record())))
                .orElseGet(VerifactuWorkerResult::empty);
    }
    // Immediately processes the newly confirmed record if it is still claimable.
}
