package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.document.SalesActivityDocumentRowView;
import com.tpverp.backend.document.SalesActivityPaymentMethod;
import com.tpverp.backend.document.SalesActivityReportService;
import com.tpverp.backend.document.SalesDailySummaryView;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SalesActivityExcelExportServiceTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 8, 16);

    @Test
    void exportsDailySummaryAndDocumentFooterAsRealExcelWorkbooks() throws Exception {
        var reports = mock(SalesActivityReportService.class);
        var organization = mock(CurrentOrganization.class);
        var company = new Company("B00000000", "EMPRESA PRUEBA", address());
        var store = new Store(
                company, "001", "Tienda", address(), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
        when(organization.currentStore()).thenReturn(store);
        when(reports.daily(REPORT_DATE)).thenReturn(new SalesDailySummaryView(
                store.getId(), company.getRazonSocial(), store.getCodigoTienda(), REPORT_DATE,
                new BigDecimal("25.00"),
                List.of(new SalesDailySummaryView.PaymentTotalView(
                        SalesActivityPaymentMethod.EFECTIVO, 1, new BigDecimal("25.00"))),
                new SalesDailySummaryView.ActivityCountsView(1, 0, 0, 0), List.of()));
        when(reports.allDocuments(REPORT_DATE, REPORT_DATE)).thenReturn(List.of(
                row("row-1", "T-001", "FV-001", "25.00"),
                row("row-2", "", "FV-002", "75.00")));
        var service = new SalesActivityExcelExportService(
                reports, organization, mock(AuditService.class));

        try (var daily = new XSSFWorkbook(new ByteArrayInputStream(service.daily(REPORT_DATE)))) {
            var sheet = daily.getSheet("Resumen diario");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Efectivo: (1)");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(sheet.getRow(7).getCell(1).getNumericCellValue()).isEqualTo(25.0);
        }
        try (var documents = new XSSFWorkbook(new ByteArrayInputStream(
                service.documents(REPORT_DATE, REPORT_DATE)))) {
            var sheet = documents.getSheet("Documentos de ventas");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(3).getCell(2).getNumericCellValue()).isEqualTo(1.0);
            assertThat(sheet.getRow(3).getCell(3).getNumericCellValue()).isEqualTo(2.0);
            assertThat(sheet.getRow(3).getCell(7).getNumericCellValue()).isEqualTo(100.0);
        }
    }

    private static SalesActivityDocumentRowView row(
            String seed, String ticket, String invoice, String total) {
        return new SalesActivityDocumentRowView(
                UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                REPORT_DATE, Instant.parse("2026-08-16T10:00:00Z"), ticket, invoice,
                UUID.randomUUID(), "CAJA", List.of("EFECTIVO"),
                SalesActivityDocumentRowView.SalesActivityKind.SALE,
                DocumentStatus.CONFIRMADO, new BigDecimal(total));
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    }
}
