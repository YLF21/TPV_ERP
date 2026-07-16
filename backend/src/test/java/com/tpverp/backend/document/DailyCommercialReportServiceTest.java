package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyCommercialReportServiceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 7, 16);

    @Test
    void separatesCurrentSalesNewDebtPriorDebtAndRealCashInflow() {
        var fixture = fixture();
        var current = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var prior = receivable(CommercialDocumentType.ALBARAN_VENTA, REPORT_DATE.minusDays(3), "80.00");
        var currentPayment = payment(fixture, current, "30.00", start(REPORT_DATE).plusSeconds(30));
        var priorPayment = payment(fixture, prior, "20.00", start(REPORT_DATE).plusSeconds(60));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(current));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(currentPayment, priorPayment));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.invoiced()).isEqualByComparingTo("100.00");
        assertThat(report.collectedCurrent()).isEqualByComparingTo("30.00");
        assertThat(report.newPending()).isEqualByComparingTo("70.00");
        assertThat(report.priorDebtCollected()).isEqualByComparingTo("20.00");
        assertThat(report.cashInflow()).isEqualByComparingTo("50.00");
    }

    @Test
    void excludesTicketsAndNonReceivableDocumentTypesFromEveryBucket() {
        var fixture = fixture();
        var invoice = receivable(CommercialDocumentType.FACTURA_VENTA, REPORT_DATE, "100.00");
        var ticket = confirmed(CommercialDocumentType.TICKET, REPORT_DATE, "40.00");
        var creditNote = confirmed(CommercialDocumentType.RECTIFICATIVA_VENTA, REPORT_DATE, "10.00");
        var purchase = confirmed(CommercialDocumentType.FACTURA_COMPRA, REPORT_DATE, "90.00");
        var invoicePayment = payment(fixture, invoice, "30.00", start(REPORT_DATE).plusSeconds(1));
        var ticketPayment = payment(fixture, ticket, "40.00", start(REPORT_DATE).plusSeconds(2));
        var creditPayment = payment(fixture, creditNote, "10.00", start(REPORT_DATE).plusSeconds(3));
        var purchasePayment = payment(fixture, purchase, "90.00", start(REPORT_DATE).plusSeconds(4));
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of(invoice, ticket, creditNote, purchase));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of(invoicePayment, ticketPayment, creditPayment, purchasePayment));

        var report = fixture.service().report(REPORT_DATE);

        assertThat(report.invoiced()).isEqualByComparingTo("100.00");
        assertThat(report.collectedCurrent()).isEqualByComparingTo("30.00");
        assertThat(report.newPending()).isEqualByComparingTo("70.00");
        assertThat(report.priorDebtCollected()).isZero();
        assertThat(report.cashInflow()).isEqualByComparingTo("30.00");
    }

    @Test
    void queriesOnlyCurrentStoreAndHalfOpenLocalDateInterval() {
        var fixture = fixture();
        when(fixture.documents().findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE))
                .thenReturn(List.of());
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE)))
                .thenReturn(List.of());

        fixture.service().report(REPORT_DATE);

        verify(fixture.documents()).findAllByTiendaIdAndFecha(fixture.store().getId(), REPORT_DATE);
        verify(fixture.payments()).findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(REPORT_DATE), end(REPORT_DATE));
    }

    private static DocumentPayment payment(
            Fixture fixture, CommercialDocument document, String amount, Instant createdAt) {
        var method = new PaymentMethod(
                fixture.store().getEmpresa().getId(), "EFECTIVO", true);
        return new DocumentPayment(
                document, method, document.getPagos().size() + 1,
                new BigDecimal(amount), document.getPagos().isEmpty(),
                null, null, null, null, createdAt);
    }

    private static CommercialDocument receivable(
            CommercialDocumentType type, LocalDate date, String amount) {
        return confirmed(type, date, amount);
    }

    private static CommercialDocument confirmed(
            CommercialDocumentType type, LocalDate date, String amount) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), type,
                date, UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P1", "Producto", "VENTA",
                new BigDecimal(amount), BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.confirm("DOC-001", UUID.randomUUID(), start(date), false);
        return document;
    }

    private static Instant start(LocalDate date) {
        return date.atStartOfDay(ZoneId.of("Atlantic/Canary")).toInstant();
    }

    private static Instant end(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneId.of("Atlantic/Canary")).toInstant();
    }

    private static Fixture fixture() {
        var store = store();
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        return new Fixture(
                new DailyCommercialReportService(documents, payments, organization),
                documents, payments, store);
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            DailyCommercialReportService service,
            CommercialDocumentRepository documents,
            DocumentPaymentRepository payments,
            Store store) {
    }
}
