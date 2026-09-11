package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerDocumentReportQueryRepository;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SaasCustomerDocumentExcelTest {
    final CurrentOrganization organization = mock(CurrentOrganization.class);
    final CustomerRepository customers = mock(CustomerRepository.class);
    final CustomerDocumentReportQueryRepository queries = mock(CustomerDocumentReportQueryRepository.class);
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final AuditService audit = mock(AuditService.class);
    final CustomerDocumentExcelExportService renderer = new CustomerDocumentExcelExportService(organization, customers, queries, jdbc, audit);

    @Test void preservesGlobalOrderAndSeparatesCurrencyTotalsWithoutTouchingLocalDatabase() throws Exception {
        var rows = List.of(row("=HYPERLINK(\"untrusted\")", "USD", "10.00"), row("EUR-2", "EUR", "20.00"), row("USD-3", "USD", "-2.00"));
        byte[] bytes = renderer.renderReceived(request(), "000042", "REMOTE-TAX", "=Remote customer", rows);
        java.nio.file.Files.write(java.nio.file.Path.of("target/customer-saas-export-preview.xlsx"), bytes);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("=Remote customer");
            assertThat(sheet.getRow(5).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo(rows.getFirst().number());
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("EUR-2");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("USD-3");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("002");
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).contains("EUR");
            assertThat(sheet.getRow(8).getCell(3).getCellFormula()).isEqualTo("SUMIFS(D6:D8,C6:C8,\"EUR\")");
            assertThat(sheet.getRow(8).getCell(3).getNumericCellValue()).isEqualTo(20.0);
            assertThat(sheet.getRow(9).getCell(3).getNumericCellValue()).isEqualTo(8.0);
            assertThat(sheet.getRow(5).getCell(3).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        }
        verifyNoInteractions(organization, customers, queries, jdbc, audit);
    }
    @Test void writesInexactLargeDecimalsAndTheirTotalAsTextNeverIgnoredBySum() throws Exception {
        String exact = "9007199254740993.01";
        byte[] bytes = renderer.renderReceived(request(), "C", "T", "Name", List.of(row("1", "EUR", exact)));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(5).getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(5).getCell(3).getStringCellValue()).isEqualTo(exact);
            assertThat(sheet.getRow(6).getCell(3).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(6).getCell(3).getStringCellValue()).isEqualTo(exact);
        }
    }
    @Test void zeroRowsRemainAValidEmptySelectionWithoutInventingACurrencyTotal() throws Exception {
        byte[] bytes = renderer.renderReceived(request(), "C", "T", "Name", List.of());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(4);
        }
    }
    @Test void receivedStatusLabelsMatchTheFiveRemoteStatesWithoutWeakeningLocalValidation() throws Exception {
        var base = request();
        var statuses = java.util.Arrays.stream(DocumentStatus.values()).filter(value -> value != DocumentStatus.BORRADOR)
                .collect(java.util.stream.Collectors.toMap(value -> value, Enum::name));
        var columns = List.of(new CustomerDocumentExportRequest.Column("status", "Estado"),
                new CustomerDocumentExportRequest.Column("currency", "Moneda"));
        var request = new CustomerDocumentExportRequest(base.customerId(), "tickets", null, null, null, List.of(), columns,
                new CustomerDocumentExportRequest.Labels("Central", base.labels().types(), statuses));
        byte[] bytes = renderer.renderReceived(request, "C", "T", "Name", List.of(row("1", "EUR", "10.00")));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getRow(5).getCell(0).getStringCellValue()).isEqualTo("PAGADO");
        }
        // The existing local route still requires its BORRADOR label as before.
        var local = new CustomerDocumentExportRequest(base.customerId(), "tickets", null, null, null, List.of(),
                List.of(new CustomerDocumentExportRequest.Column("status", "Estado")), request.labels());
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator", "",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("TICKETS_READ")));
        assertThatThrownBy(() -> renderer.export(local, authentication)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Las etiquetas de exportación no son válidas");
        verifyNoInteractions(organization, customers, queries, jdbc, audit);
    }
    private CustomerDocumentExportRequest request() {
        return new CustomerDocumentExportRequest(UUID.randomUUID(), "tickets", null, null, null, null,
                List.of(new CustomerDocumentExportRequest.Column("number", "Número"), new CustomerDocumentExportRequest.Column("store", "Tienda"),
                        new CustomerDocumentExportRequest.Column("currency", "Moneda"), new CustomerDocumentExportRequest.Column("total", "Total")),
                new CustomerDocumentExportRequest.Labels("Documentos SaaS", Map.of(CommercialDocumentType.TICKET, "Ticket"), Map.of()));
    }
    private static CustomerDocumentExcelExportService.ExportRow row(String number, String currency, String amount) {
        return new CustomerDocumentExcelExportService.ExportRow(UUID.randomUUID(), number, LocalDate.of(2026, 9, 10),
                CommercialDocumentType.TICKET, DocumentStatus.PAGADO, new BigDecimal(amount), BigDecimal.ZERO,
                new BigDecimal(amount), "Caja central", "Actor histórico", "002", currency);
    }
}
