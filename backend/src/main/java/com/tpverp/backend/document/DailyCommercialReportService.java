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
    private final CurrentOrganization organization;

    public DailyCommercialReportService(
            CommercialDocumentRepository documents,
            DocumentPaymentRepository payments,
            CurrentOrganization organization) {
        this.documents = documents;
        this.payments = payments;
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
        var issuedTotal = issued.stream()
                .filter(DailyCommercialReportService::isCommercialSummaryDocument)
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var sameDayCollected = issued.stream()
                .filter(DailyCommercialReportService::isCommercialSummaryDocument)
                .flatMap(document -> document.getPagos().stream())
                .filter(payment -> !payment.getCreadoEn().isBefore(from) && payment.getCreadoEn().isBefore(to))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var collectedTotal = collected.stream()
                .filter(payment -> isSalesDocument(payment.getDocumento()))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var previousPendingCollected = collected.stream()
                .filter(payment -> isSalesDocument(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().isBefore(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DailyCommercialReportView(
                store.getId(),
                date,
                Money.euros(issuedTotal),
                Money.euros(collectedTotal),
                Money.euros(issuedTotal.subtract(sameDayCollected)),
                Money.euros(previousPendingCollected));
    }

    private static boolean isCommercialSummaryDocument(CommercialDocument document) {
        return document.getEstado() != DocumentStatus.BORRADOR
                && document.getEstado() != DocumentStatus.ANULADO
                && isSalesDocument(document);
    }

    private static boolean isSalesDocument(CommercialDocument document) {
        return document.getTipo() == CommercialDocumentType.TICKET
                || document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                || document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA;
    }
}
