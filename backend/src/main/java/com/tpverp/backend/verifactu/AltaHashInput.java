package com.tpverp.backend.verifactu;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AltaHashInput(
        String issuerTaxId,
        String invoiceNumber,
        String issueDate,
        String invoiceType,
        BigDecimal totalTax,
        BigDecimal totalAmount,
        String previousHash,
        OffsetDateTime generatedAt) {

    // Valida y normaliza los valores textuales exactos usados por la huella fiscal.
    public AltaHashInput {
        issuerTaxId = requiredText(issuerTaxId, "issuerTaxId");
        invoiceNumber = requiredText(invoiceNumber, "invoiceNumber");
        issueDate = requiredText(issueDate, "issueDate");
        invoiceType = requiredText(invoiceType, "invoiceType");
        totalTax = required(totalTax, "totalTax");
        totalAmount = required(totalAmount, "totalAmount");
        previousHash = optionalText(previousHash, "previousHash");
        generatedAt = required(generatedAt, "generatedAt");
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

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value;
    }
}
