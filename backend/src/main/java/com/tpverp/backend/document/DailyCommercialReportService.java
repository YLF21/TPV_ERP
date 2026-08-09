package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        return report(date, (UUID) null);
    }

    @Transactional(readOnly = true)
    public DailyCommercialReportView report(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null) {
            throw new IllegalArgumentException("dateFrom es obligatorio");
        }
        if (dateTo == null) {
            dateTo = dateFrom;
        }
        if (dateTo.isBefore(dateFrom)) {
            throw new IllegalArgumentException("dateTo no puede ser anterior a dateFrom");
        }
        var storeId = organization.currentStore().getId();
        var invoiced = BigDecimal.ZERO;
        var ticketSales = BigDecimal.ZERO;
        var collectedCurrent = BigDecimal.ZERO;
        var newPending = BigDecimal.ZERO;
        var priorDebtCollected = BigDecimal.ZERO;
        var cashInflow = BigDecimal.ZERO;
        var days = new ArrayList<DailyCommercialReportDayView>();
        for (var date = dateFrom; !date.isAfter(dateTo); date = date.plusDays(1)) {
            var daily = report(date, (UUID) null);
            invoiced = invoiced.add(daily.invoiced());
            ticketSales = ticketSales.add(daily.ticketSales());
            collectedCurrent = collectedCurrent.add(daily.collectedCurrent());
            newPending = newPending.add(daily.newPending());
            priorDebtCollected = priorDebtCollected.add(daily.priorDebtCollected());
            cashInflow = cashInflow.add(daily.cashInflow());
            days.add(new DailyCommercialReportDayView(
                    daily.date(), daily.invoiced(), daily.ticketSales(), daily.collectedCurrent(),
                    daily.newPending(), daily.priorDebtCollected(), daily.cashInflow()));
        }
        return new DailyCommercialReportView(
                storeId, dateFrom, Money.euros(invoiced), Money.euros(ticketSales),
                Money.euros(collectedCurrent), Money.euros(newPending),
                Money.euros(priorDebtCollected), Money.euros(cashInflow), List.copyOf(days));
    }

    @Transactional(readOnly = true)
    public DailyCommercialReportView report(LocalDate date, UUID warehouseId) {
        if (date == null) {
            throw new IllegalArgumentException("date es obligatorio");
        }
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var from = date.atStartOfDay(zone).toInstant();
        var to = date.plusDays(1).atStartOfDay(zone).toInstant();
        var issued = documents.findAllByTiendaIdAndFecha(store.getId(), date).stream()
                .filter(document -> warehouseId == null || warehouseId.equals(document.getAlmacenId()))
                .toList();
        var collected = payments.findAllByStoreAndCreatedBetween(store.getId(), from, to).stream()
                .filter(payment -> warehouseId == null
                        || warehouseId.equals(payment.getDocumento().getAlmacenId()))
                .toList();
        var invoicedOrigins = relations.findInvoicedOriginIds(store.getId(), date);
        var invoiced = issued.stream()
                .filter(DailyCommercialReportService::isCustomerReceivableSale)
                .filter(document -> !invoicedOrigins.contains(document.getId()))
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var ticketSales = issued.stream()
                .filter(DailyCommercialReportService::isTicketSale)
                .filter(document -> !invoicedOrigins.contains(document.getId()))
                .map(CommercialDocument::getTotal)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var receivableCollectedCurrent = collected.stream()
                .filter(payment -> isCustomerReceivableSale(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().equals(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var ticketCollectedCurrent = collected.stream()
                .filter(payment -> isTicketSale(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().equals(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var collectedCurrent = receivableCollectedCurrent.add(ticketCollectedCurrent);
        var priorDebtCollected = collected.stream()
                .filter(payment -> isCustomerReceivableSale(payment.getDocumento()))
                .filter(payment -> payment.getDocumento().getFecha().isBefore(date))
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var newPending = invoiced.subtract(receivableCollectedCurrent).max(BigDecimal.ZERO);
        var cashInflow = collectedCurrent.add(priorDebtCollected);
        return new DailyCommercialReportView(
                store.getId(),
                date,
                Money.euros(invoiced),
                Money.euros(ticketSales),
                Money.euros(collectedCurrent),
                Money.euros(newPending),
                Money.euros(priorDebtCollected),
                Money.euros(cashInflow),
                List.of());
    }

    private static boolean isCustomerReceivableSale(CommercialDocument document) {
        return document.getEstado() != DocumentStatus.BORRADOR
                && document.getEstado() != DocumentStatus.ANULADO
                && (document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                || document.getTipo() == CommercialDocumentType.FACTURA_VENTA);
    }

    private static boolean isTicketSale(CommercialDocument document) {
        return document.getEstado() != DocumentStatus.BORRADOR
                && document.getEstado() != DocumentStatus.ANULADO
                && document.getTipo() == CommercialDocumentType.TICKET;
    }
}
