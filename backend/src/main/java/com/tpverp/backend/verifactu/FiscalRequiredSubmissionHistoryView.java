package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

/** Safe metadata projection for an AEAT NO VERI*FACTU requirement. */
public record FiscalRequiredSubmissionHistoryView(
        UUID id,
        UUID companyId,
        UUID installationId,
        String reference,
        Instant requestedAt,
        Instant attendedAt,
        UUID exportId,
        String status) {

    public static FiscalRequiredSubmissionHistoryView from(FiscalRequiredSubmission submission) {
        return new FiscalRequiredSubmissionHistoryView(
                submission.getId(), submission.getCompanyId(), submission.getInstallationId(),
                submission.getReference(), submission.getRequestedAt(), submission.getAttendedAt(),
                submission.getExportId(), submission.getStatus());
    }
}
