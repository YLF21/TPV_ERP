package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @Test
    void separatesDebtGeneratedFromDebtCollectedLater() {
        var fixture = fixture();
        var saleDay = LocalDate.of(2026, 7, 5);
        var paymentDay = LocalDate.of(2026, 8, 5);
        var invoice = receivable(saleDay, "500.00");
        var cash = new PaymentMethod(fixture.store().getEmpresa().getId(), "EFECTIVO", true);
        invoice.addPayment(new DocumentPayment(
                invoice, cash, 1, new BigDecimal("500.00"), true, null, null,
                paymentDay.atStartOfDay(ZoneId.of("Atlantic/Canary")).toInstant()));
        when(fixture.documents().findAllByTiendaIdAndFecha(
                fixture.store().getId(), saleDay)).thenReturn(List.of(invoice));
        when(fixture.payments().findAllByStoreAndCreatedBetween(
                fixture.store().getId(), start(paymentDay), end(paymentDay))).thenReturn(invoice.getPagos());

        var saleReport = fixture.service().report(saleDay);
        var paymentReport = fixture.service().report(paymentDay);

        assertThat(saleReport.issuedTotal()).isEqualByComparingTo("500.00");
        assertThat(saleReport.collectedTotal()).isEqualByComparingTo("0.00");
        assertThat(saleReport.generatedPendingTotal()).isEqualByComparingTo("500.00");
        assertThat(saleReport.collectedPreviousPendingTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentReport.issuedTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentReport.collectedTotal()).isEqualByComparingTo("500.00");
        assertThat(paymentReport.generatedPendingTotal()).isEqualByComparingTo("0.00");
        assertThat(paymentReport.collectedPreviousPendingTotal()).isEqualByComparingTo("500.00");
    }

    private static CommercialDocument receivable(LocalDate date, String amount) {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                date, UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P1", "Producto", "VENTA",
                new BigDecimal(amount), BigDecimal.ZERO, true, "IVA",
                BigDecimal.ZERO));
        document.confirm("FV-001-26-000001", UUID.randomUUID(), Instant.now(), false);
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
