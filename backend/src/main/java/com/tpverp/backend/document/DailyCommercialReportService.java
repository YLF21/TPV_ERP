package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyCommercialReportService {

    private final CommercialDocumentRepository documents;
    private final DocumentPaymentRepository payments;
    private final DocumentRelationRepository relations;
    private final CurrentOrganization organization;

    public DailyCommercialReportService(
            CommercialDocumentRepository documents,
            DocumentPaymentRepository payments,
            DocumentRelationRepository relations,
            CurrentOrganization organization) {
        this.documents = documents;
        this.payments = payments;
        this.relations = relations;
        this.organization = organization;
    }

    // Calculates commercial activity by issue date and real payment date.
    @Transactional(readOnly = true)
    public DailyCommercialReportView report(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date es obligatorio");
        }
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var from = date.atStartOfDay(zone).toInstant();
        var to = date.plusDays(1).atStartOfDay(zone).toInstant();
        var issued = documents.findAllByTiendaIdAndFecha(store.getId(), date);
        var collected = payments.findAllByStoreAndCreatedBetween(store.getId(), from, to);
        var invoicedOrigins = relations.findInvoicedOriginIds(store.getId());
        var invoiced = issued.stream()
                .filter(DailyCommercialReportService::isCustomerReceivableSale)
                .filter(document -> !invoicedOrigins.contains(document.getId()))
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var collectedCurrent = collected.stream()
                .filter(payment -> isCustomerReceivableSale(payment.getDocumento()))
                .filter(payment -> !invoicedOrigins.contains(payment.getDocumento().getId()))
                .filter(payment -> payment.getDocumento().getFecha().equals(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var priorDebtCollected = collected.stream()
                .filter(payment -> isCustomerReceivableSale(payment.getDocumento()))
                .filter(payment -> !invoicedOrigins.contains(payment.getDocumento().getId()))
                .filter(payment -> payment.getDocumento().getFecha().isBefore(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var newPending = invoiced.subtract(collectedCurrent).max(BigDecimal.ZERO);
        var cashInflow = collectedCurrent.add(priorDebtCollected);
        return new DailyCommercialReportView(
                store.getId(),
                date,
                Money.euros(invoiced),
                Money.euros(collectedCurrent),
                Money.euros(newPending),
                Money.euros(priorDebtCollected),
                Money.euros(cashInflow));
    }

    private static boolean isCustomerReceivableSale(CommercialDocument document) {
        return document.getEstado() != DocumentStatus.BORRADOR
                && document.getEstado() != DocumentStatus.ANULADO
                && (document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA);
    }
}
