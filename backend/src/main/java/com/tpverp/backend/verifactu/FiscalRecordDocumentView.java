package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Non-sensitive reference to the operational document behind a fiscal record. */
public record FiscalRecordDocumentView(
        UUID id,
        UUID storeId,
        CommercialDocumentType type,
        DocumentStatus status,
        String number,
        LocalDate issueDate,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt) {
}
