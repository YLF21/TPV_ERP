package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentAttributionResolver;
import com.tpverp.backend.document.DocumentReportView;
import com.tpverp.backend.document.DocumentReportService;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.inventory.WarehouseInputService;
import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseOutputService;
import com.tpverp.backend.inventory.WarehouseOutput;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SalesReportExcelExportServiceTest {

    @Test
    void exportsInvoiceReportWithDocumentTypeAndMoneyColumns() throws Exception {
        var reports = mock(DocumentReportService.class);
        var invoice = mock(DocumentReportView.class);
        when(invoice.tipo()).thenReturn(CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(invoice.estado()).thenReturn(DocumentStatus.PENDIENTE);
        when(invoice.numero()).thenReturn("FRV-001");
        when(invoice.fecha()).thenReturn(LocalDate.of(2026, 7, 27));
        when(invoice.pendiente()).thenReturn(new BigDecimal("-12.10"));
        when(invoice.total()).thenReturn(new BigDecimal("-12.10"));
        when(invoice.payments()).thenReturn(List.of());
        when(reports.allInvoices(true, false)).thenReturn(List.of(invoice));
        var service = new SalesReportExcelExportService(
                mock(DocumentService.class),
                reports,
                mock(WarehouseInputService.class),
                mock(WarehouseOutputService.class),
                mock(WarehouseRepository.class),
                mock(CurrentOrganization.class),
                mock(DocumentAttributionResolver.class),
                mock(AuditService.class));
        var authentication = new UsernamePasswordAuthenticationToken(
                "manager", "token", List.of(new SimpleGrantedAuthority("GESTION_VENTAS")));
        var request = new SalesReportExportRequest(
                "salesReport.invoices",
                new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", ""),
                "",
                List.of(
                        new SalesReportExportRequest.Column("invoice", "Factura"),
                        new SalesReportExportRequest.Column("documentType", "Tipo de factura"),
                        new SalesReportExportRequest.Column("pending", "Pendiente"),
                        new SalesReportExportRequest.Column("total", "Total")));

        byte[] result = service.export(request, authentication);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("FRV-001");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("RECTIFICATIVA_VENTA");
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(-12.10);
            assertThat(row.getCell(2).getCellStyle().getDataFormatString()).contains("€");
            assertThat(row.getCell(3).getCellStyle().getDataFormatString()).contains("€");
        }
    }

    @Test
    void exportsAllAuthoritativeRowsMatchingTheRequestedDateRange() throws Exception {
        var documents = mock(DocumentService.class);
        var organization = mock(CurrentOrganization.class);
        var attributions = mock(DocumentAttributionResolver.class);
        var authentication = new UsernamePasswordAuthenticationToken(
                "manager", "token", List.of(new SimpleGrantedAuthority("GESTION_VENTAS")));
        var terminalId = UUID.randomUUID();
        var currentTicket = ticket(LocalDate.of(2026, 7, 18));
        var previousTicket = ticket(LocalDate.of(2026, 7, 17));
        var tickets = List.of(currentTicket, previousTicket);
        when(documents.listTickets()).thenReturn(tickets);
        when(attributions.resolve(tickets)).thenReturn(Map.of(
                currentTicket.getId(), new DocumentAttributionResolver.Attribution(
                        UUID.randomUUID(), "Cajero historico", terminalId, "CAJA 02", null),
                previousTicket.getId(), new DocumentAttributionResolver.Attribution(
                        UUID.randomUUID(), "Otro cajero", UUID.randomUUID(), "CAJA 03", null)));
        var service = new SalesReportExcelExportService(
                documents,
                mock(DocumentReportService.class),
                mock(WarehouseInputService.class),
                mock(WarehouseOutputService.class),
                mock(WarehouseRepository.class),
                organization,
                attributions,
                mock(AuditService.class));
        var request = new SalesReportExportRequest(
                "salesReport.tickets",
                new SalesReportExportRequest.Filters(
                        "2026-07-18", "2026-07-18", "", "", "", "", "", "", ""),
                "",
                List.of(
                        new SalesReportExportRequest.Column("date", "Fecha"),
                        new SalesReportExportRequest.Column("terminal", "Terminal")));

        byte[] result = service.export(request, authentication);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("18/07/2026");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("CAJA 02");
        }
    }

    @Test
    void exportsWarehouseNamesAndHistoricalPurchaseAndSaleTotals() throws Exception {
        var inputs = mock(WarehouseInputService.class);
        var outputs = mock(WarehouseOutputService.class);
        var warehouses = mock(WarehouseRepository.class);
        var storeId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var warehouse = Warehouse.general(storeId);
        var input = new WarehouseInput(storeId, warehouse.getId(), LocalDate.of(2026, 7, 20), userId);
        input.addLine(productId, 6);
        input.snapshotPurchasePrices(Map.of(productId, new BigDecimal("4.20")));
        input.confirm("ENT-2026-TEST", userId, java.time.Instant.parse("2026-07-20T10:00:00Z"));
        var output = new WarehouseOutput(storeId, warehouse.getId(), LocalDate.of(2026, 7, 20), userId);
        output.addLine(productId, 3);
        output.snapshotSalePrices(Map.of(productId, new BigDecimal("10.25")));
        output.confirm("SAL-2026-TEST", userId, java.time.Instant.parse("2026-07-20T11:00:00Z"));
        when(inputs.list()).thenReturn(List.of(input));
        when(outputs.list()).thenReturn(List.of(output));
        when(warehouses.findAll()).thenReturn(List.of(warehouse));
        var service = new SalesReportExcelExportService(
                mock(DocumentService.class),
                mock(DocumentReportService.class),
                inputs,
                outputs,
                warehouses,
                mock(CurrentOrganization.class),
                mock(DocumentAttributionResolver.class),
                mock(AuditService.class));
        var authentication = new UsernamePasswordAuthenticationToken(
                "warehouse", "token", List.of(new SimpleGrantedAuthority("GESTION_ALMACEN")));

        assertWarehouseExport(service, authentication, "salesReport.inputWarehouse", "GENERAL", 25.20);
        assertWarehouseExport(service, authentication, "salesReport.warehouseOutputs", "GENERAL", 30.75);
    }

    private void assertWarehouseExport(
            SalesReportExcelExportService service,
            UsernamePasswordAuthenticationToken authentication,
            String reportKey,
            String expectedWarehouse,
            double expectedTotal) throws Exception {
        var request = new SalesReportExportRequest(
                reportKey,
                new SalesReportExportRequest.Filters("", "", "", "", "", "", "", "", ""),
                "",
                List.of(
                        new SalesReportExportRequest.Column("warehouse", "Almacén"),
                        new SalesReportExportRequest.Column("total", "Total")));

        byte[] result = service.export(request, authentication);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(expectedWarehouse);
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(expectedTotal);
            assertThat(row.getCell(1).getCellStyle().getDataFormatString()).contains("€");
        }
    }

    private CommercialDocument ticket(LocalDate date) {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                date, UUID.randomUUID(), BigDecimal.ZERO);
    }
}
