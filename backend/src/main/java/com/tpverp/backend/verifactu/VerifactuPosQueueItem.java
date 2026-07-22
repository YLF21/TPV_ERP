package com.tpverp.backend.verifactu;

import java.time.Instant;
public record VerifactuPosQueueItem(
        String documentNumber,
        FiscalDocumentType documentType,
        FiscalSubmissionStatus submissionStatus,
        Instant updatedAt,
        String operationalMessageCode) {

    static VerifactuPosQueueItem from(VerifactuPosQueueRecord record) {
        return new VerifactuPosQueueItem(
                record.documentNumber(),
                record.documentType(),
                record.submissionStatus(),
                record.updatedAt(),
                requiresReview(record.submissionStatus())
                        ? "VERIFACTU_REVIEW_REQUIRED"
                        : null);
    }

    private static boolean requiresReview(FiscalSubmissionStatus status) {
        return status == FiscalSubmissionStatus.RECHAZADO
                || status == FiscalSubmissionStatus.DEFECTUOSO
                || status == FiscalSubmissionStatus.ACEPTADO_CON_ERRORES;
    }
}
