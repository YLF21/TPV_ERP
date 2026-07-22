package com.tpverp.backend.verifactu;

import java.time.Instant;
/** Internal read model. It deliberately contains no fiscal payload or party data. */
public record VerifactuPosQueueRecord(
        String documentNumber,
        FiscalDocumentType documentType,
        FiscalSubmissionStatus submissionStatus,
        Instant updatedAt) {
}
