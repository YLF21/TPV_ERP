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
}
