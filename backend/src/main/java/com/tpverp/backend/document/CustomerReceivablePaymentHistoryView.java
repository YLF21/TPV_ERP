package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerReceivablePaymentHistoryView(
        UUID paymentId,
        UUID requestId,
        UUID documentId,
        CommercialDocumentType documentType,
        String documentNumber,
        UUID customerId,
        String customerName,
        LocalDate issueDate,
        Instant collectedAt,
        UUID paymentMethodId,
        String paymentMethodName,
        BigDecimal amount,
        String reference) {

    public static CustomerReceivablePaymentHistoryView from(
            DocumentPayment payment, String customerName) {
        var document = payment.getDocumento();
        var method = payment.getMetodoPago();
        return new CustomerReceivablePaymentHistoryView(
                payment.getId(), payment.getRequestId(), document.getId(),
                document.getTipo(), document.getNumero(), document.getClienteId(),
                customerName, document.getFecha(), payment.getCreadoEn(), method.getId(),
                method.getNombre(), payment.getImporte(), payment.getReferencia());
    }
}
