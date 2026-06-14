package com.tpverp.backend.verifactu;

import java.time.OffsetDateTime;

public record CancellationHashInput(
        String issuerTaxId,
        String cancelledInvoiceNumber,
        String cancelledIssueDate,
        String previousHash,
        OffsetDateTime generatedAt) {
}
