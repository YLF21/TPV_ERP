package com.tpverp.backend.verifactu;

import java.time.OffsetDateTime;

public record CancellationHashInput(
        String issuerTaxId,
        String cancelledInvoiceNumber,
        String cancelledIssueDate,
        String previousHash,
        OffsetDateTime generatedAt) {

    // Valida y normaliza los valores textuales exactos usados por la huella fiscal.
    public CancellationHashInput {
        issuerTaxId = requiredText(issuerTaxId, "issuerTaxId");
        cancelledInvoiceNumber = requiredText(cancelledInvoiceNumber, "cancelledInvoiceNumber");
        cancelledIssueDate = requiredText(cancelledIssueDate, "cancelledIssueDate");
        previousHash = optionalText(previousHash, "previousHash");
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt es obligatorio");
        }
    }

    private static String requiredText(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }

    private static String optionalText(String value, String field) {
        return value == null ? null : requiredText(value, field);
    }
}
