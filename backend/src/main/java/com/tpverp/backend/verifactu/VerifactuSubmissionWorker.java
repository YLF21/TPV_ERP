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
    // Procesa un unico registro para evitar lotes largos y facilitar reintentos controlados.
}
