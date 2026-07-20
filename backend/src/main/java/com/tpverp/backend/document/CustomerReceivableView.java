package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerReceivableView(
        UUID documentId,
        CommercialDocumentType documentType,
        String documentNumber,
        UUID customerId,
        String customerName,
        LocalDate issueDate,
        LocalDate dueDate,
        BigDecimal total,
        BigDecimal paidTotal,
        BigDecimal pendingTotal,
        DocumentStatus status,
        boolean overdue) {

    public static CustomerReceivableView from(
            CommercialDocument document, String customerName, LocalDate businessDate) {
        if (document.getTipo() != CommercialDocumentType.ALBARAN_VENTA
                && document.getTipo() != CommercialDocumentType.FACTURA_VENTA) {
            throw new IllegalArgumentException(
                    "message.document.only_receivable_document_can_be_paid");
        }
        var pending = document.getPendingTotal();
        var status = pending.signum() == 0
                ? DocumentStatus.PAGADO : document.getEstado();
        return new CustomerReceivableView(
                document.getId(), document.getTipo(), document.getNumero(),
                document.getClienteId(), customerName, document.getFecha(),
                document.getDueDate(), document.getTotal(), document.getPaidTotal(),
                pending, status, pending.signum() > 0
                        && document.getDueDate() != null
                        && document.getDueDate().isBefore(businessDate));
    }
}
