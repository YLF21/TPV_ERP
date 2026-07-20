package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentAttributionResolver;
import com.tpverp.backend.document.DocumentReportService;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.inventory.WarehouseInputService;
import com.tpverp.backend.inventory.WarehouseOutputService;
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

    private CommercialDocument ticket(LocalDate date) {
        return new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                date, UUID.randomUUID(), BigDecimal.ZERO);
    }
}
