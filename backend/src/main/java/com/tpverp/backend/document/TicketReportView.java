package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TicketReportView(
        UUID id,
        CommercialDocumentType tipo,
        DocumentStatus estado,
        String numero,
        LocalDate fecha,
        BigDecimal base,
        BigDecimal impuesto,
        BigDecimal total,
        BigDecimal effectiveTotal,
        BigDecimal descuentoGlobal,
        BigDecimal memberBalance,
        UUID customerId,
        String customerCode,
        String customerName,
        UUID usuarioId,
        String usuarioNombre,
        UUID terminalOrigenId,
        String terminalOrigenNombre,
        Instant ocurridoEn,
        List<String> paymentMethods,
        List<RefundTenderType> refundMethods,
        String invoiceNumber,
        TicketReportLifecycleStatus lifecycleStatus,
        String comentarioInterno) {
}
