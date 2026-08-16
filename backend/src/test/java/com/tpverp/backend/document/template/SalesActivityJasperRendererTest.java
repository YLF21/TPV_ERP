package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.SalesActivityDocumentRowView;
import com.tpverp.backend.document.SalesActivityPaymentMethod;
import com.tpverp.backend.document.SalesActivityPrintGrouping;
import com.tpverp.backend.document.SalesDailySummaryView;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class SalesActivityJasperRendererTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 8, 16);

    @Test
    void rendersDailyAndDocumentReportsInA4AndTicketFormats() throws Exception {
        var renderer = renderer();
        var daily = new SalesDailySummaryView(
                UUID.randomUUID(), "EMPRESA PRUEBA", "001", REPORT_DATE,
                new BigDecimal("100.00"),
                List.of(new SalesDailySummaryView.PaymentTotalView(
                        SalesActivityPaymentMethod.EFECTIVO, 1, new BigDecimal("100.00"))),
                new SalesDailySummaryView.ActivityCountsView(1, 0, 0, 0),
                List.of(new SalesDailySummaryView.UserSummaryView(
                        UUID.randomUUID(), "ADMIN", new BigDecimal("100.00"),
                        List.of(new SalesDailySummaryView.PaymentTotalView(
                                SalesActivityPaymentMethod.EFECTIVO, 1,
                                new BigDecimal("100.00"))),
                        new SalesDailySummaryView.ActivityCountsView(1, 0, 0, 0))));
        var row = new SalesActivityDocumentRowView(
                UUID.randomUUID(), REPORT_DATE, Instant.parse("2026-08-16T10:15:00Z"),
                "T-001", "FV-001", UUID.randomUUID(), "CAJA",
                List.of("EFECTIVO"), SalesActivityDocumentRowView.SalesActivityKind.SALE,
                DocumentStatus.CONFIRMADO, new BigDecimal("100.00"));

        var dailyA4 = renderer.renderDaily(daily, DocumentTemplateFormat.A4);
        var dailyTicket = renderer.renderDaily(daily, DocumentTemplateFormat.TICKET_80);
        var documentsA4 = renderer.renderDocuments(
                List.of(row), REPORT_DATE, REPORT_DATE,
                SalesActivityPrintGrouping.DAY, DocumentTemplateFormat.A4);
        var documentsTicket = renderer.renderDocuments(
                List.of(row), REPORT_DATE, REPORT_DATE,
                SalesActivityPrintGrouping.DOCUMENT, DocumentTemplateFormat.TICKET_80);

        assertPdf(dailyA4.pdf());
        assertPdf(dailyTicket.pdf());
        assertPdf(documentsA4.pdf());
        assertPdf(documentsTicket.pdf());
        assertThat(dailyA4.ticketRasterPng()).isNull();
        assertThat(documentsA4.ticketRasterPng()).isNull();
        assertPng(dailyTicket.ticketRasterPng());
        assertPng(documentsTicket.ticketRasterPng());
        try (var dailyPdf = Loader.loadPDF(dailyA4.pdf());
             var documentsPdf = Loader.loadPDF(documentsA4.pdf());
             var dailyTicketPdf = Loader.loadPDF(dailyTicket.pdf());
             var documentsTicketPdf = Loader.loadPDF(documentsTicket.pdf())) {
            assertThat(new PDFTextStripper().getText(dailyPdf))
                    .contains("EMPRESA PRUEBA", "EFECTIVO: (1)", "TOTAL", "VENTAS", "100.00€")
                    .doesNotContain("T-001");
            assertThat(new PDFTextStripper().getText(documentsPdf))
                    .contains("DOCUMENTOS DE VENTAS", "T-001", "FV-001", "100.00€");
            assertThat(new PDFTextStripper().getText(dailyTicketPdf))
                    .contains("RESUMEN DE VENTAS DIARIAS", "EFECTIVO: (1)", "TOTAL:",
                            "VENTAS", "USUARIO: ADMIN", "100.00€")
                    .doesNotContain("TOTAL TIENDA");
            assertThat(new PDFTextStripper().getText(documentsTicketPdf))
                    .contains("DOCUMENTOS DE VENTAS - POR DOCUMENTO", "16/08/2026",
                            "T-001", "CONFIRMADO", "100.00€")
                    .doesNotContain("FV-001");
        }
    }

    @Test
    void rendersTicketDocumentsByDocumentAndByDay() throws Exception {
        var renderer = renderer();
        var rows = List.of(
                row(REPORT_DATE, "001-260816-00009", "-16.40",
                        SalesActivityDocumentRowView.SalesActivityKind.RETURN,
                        DocumentStatus.CONFIRMADO),
                row(REPORT_DATE, "001-260816-00008", "0.00",
                        SalesActivityDocumentRowView.SalesActivityKind.CANCELLED,
                        DocumentStatus.ANULADO),
                row(REPORT_DATE, "001-260816-00007", "16.40",
                        SalesActivityDocumentRowView.SalesActivityKind.SALE,
                        DocumentStatus.CONFIRMADO),
                row(REPORT_DATE.minusDays(1), "001-260815-00023", "25.00",
                        SalesActivityDocumentRowView.SalesActivityKind.SALE,
                        DocumentStatus.CONFIRMADO));

        var byDocument = renderer.renderDocuments(
                rows, REPORT_DATE.minusDays(1), REPORT_DATE,
                SalesActivityPrintGrouping.DOCUMENT, DocumentTemplateFormat.TICKET_80);
        var byDay = renderer.renderDocuments(
                rows, REPORT_DATE.minusDays(1), REPORT_DATE,
                SalesActivityPrintGrouping.DAY, DocumentTemplateFormat.TICKET_80);

        try (var documentPdf = Loader.loadPDF(byDocument.pdf());
             var dayPdf = Loader.loadPDF(byDay.pdf())) {
            assertThat(new PDFTextStripper().getText(documentPdf))
                    .contains("16/08/2026", "001-260816-00009", "DEVOLUCION", "-16.40€",
                            "001-260816-00008", "ANULADO", "0.00€",
                            "001-260816-00007", "CONFIRMADO", "16.40€",
                            "15/08/2026", "001-260815-00023", "25.00€");
            assertThat(new PDFTextStripper().getText(dayPdf))
                    .contains("DOCUMENTOS DE VENTAS - POR DIA", "16/08/2026", "0.00€",
                            "15/08/2026", "25.00€", "TOTAL:");
        }
    }

    private static void assertPdf(byte[] value) {
        assertThat(value).isNotEmpty();
        assertThat(new String(value, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    private static void assertPng(byte[] value) {
        assertThat(value).isNotEmpty();
        assertThat(value).startsWith(
                (byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
    }

    private static SalesActivityDocumentRowView row(
            LocalDate date,
            String ticketNumber,
            String total,
            SalesActivityDocumentRowView.SalesActivityKind kind,
            DocumentStatus status) {
        return new SalesActivityDocumentRowView(
                UUID.randomUUID(), date, date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                ticketNumber, "", UUID.randomUUID(), "ADMIN", List.of("EFECTIVO"),
                kind, status, new BigDecimal(total));
    }

    private static SalesActivityJasperRenderer renderer() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B00000000", "EMPRESA PRUEBA", address);
        var store = new Store(
                company, "001", "Tienda", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        return new SalesActivityJasperRenderer(
                new SafeJrxmlCompiler(), new ObjectMapper(), organization);
    }
}
