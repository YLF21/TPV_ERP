package com.tpverp.backend.excel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SalesReportExcelExportController.class)
@Import(SalesReportExcelExportControllerContractTest.MethodSecurityConfiguration.class)
class SalesReportExcelExportControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private SalesReportExcelExportService service;
    @MockitoBean private SalesReportPdfExportService pdfService;

    @Test
    void salesManagementCanExportAFilteredWorkbook() throws Exception {
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});

        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString("Tickets.xlsx")));
    }

    @Test
    void posSalesCannotExportManagementReports() throws Exception {
        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("seller").authorities(() -> "VENTA"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void salesManagementCanExportPdf() throws Exception {
        when(pdfService.export(any(), any())).thenReturn("%PDF".getBytes());

        mvc.perform(post("/api/v1/sales-reports/export-pdf")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("Tickets.pdf")));
    }

    @ParameterizedTest
    @CsvSource({"tickets, Tickets", "invoices, Facturas", "deliveryNotes, Albaranes",
            "inputInvoices, Facturas_de_compra", "inputDeliveryNotes, Albaranes_de_compra"})
    void salesDocumentExportsIncludeTheDateRangeInBothFilenames(String report, String stem) throws Exception {
        String authority = report.startsWith("input") ? "GESTION_ALMACEN" : "GESTION_VENTAS";
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});
        when(pdfService.export(any(), any())).thenReturn("%PDF".getBytes());
        String body = ticketRequestBody("2026-03-07", "2026-11-14").replace("salesReport.tickets", "salesReport." + report);

        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("manager").authorities(() -> authority))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(stem + "_070326-141126.xlsx")));

        mvc.perform(post("/api/v1/sales-reports/export-pdf")
                        .with(user("manager").authorities(() -> authority))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(stem + "_070326-141126.pdf")));
    }

    @ParameterizedTest
    @CsvSource({"tickets, Tickets", "invoices, Facturas", "deliveryNotes, Albaranes",
            "inputInvoices, Facturas_de_compra", "inputDeliveryNotes, Albaranes_de_compra"})
    void salesDocumentExportsIncludeTheRangeWhenBothDatesAreTheSame(String report, String stem) throws Exception {
        String authority = report.startsWith("input") ? "GESTION_ALMACEN" : "GESTION_VENTAS";
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});

        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("manager").authorities(() -> authority))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketRequestBody("2026-11-14", "2026-11-14").replace("salesReport.tickets", "salesReport." + report)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(stem + "_141126-141126.xlsx")));
    }

    @Test
    void invalidTicketDatesFallBackToBaseFilenameWithoutBreakingTheExport() throws Exception {
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});

        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ticketRequestBody("not-a-date", "2026-11-14")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("Tickets.xlsx")));
    }

    @Test
    void acceptsMultipleValuesForEveryReportFilter() throws Exception {
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});
        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportKey":"salesReport.invoices", "filters": {
                                  "users":["Ana","Luis"], "customers":["C-001","C-002"],
                                  "suppliers":["P-001","P-002"], "payments":["EFECTIVO","TARJETA"],
                                  "terminals":["CAJA 1","CAJA 2"], "statuses":["PAGADO","PARCIAL"],
                                  "warehouses":["GENERAL","RESERVA"]
                                }, "columns":[{"key":"invoice","label":"Factura"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("Facturas.xlsx")));

        verify(service).export(argThat(request -> request.filters().users().size() == 2
                && request.filters().customers().size() == 2 && request.filters().suppliers().size() == 2
                && request.filters().payments().equals(java.util.List.of("EFECTIVO", "TARJETA"))
                && request.filters().terminals().size() == 2 && request.filters().statuses().size() == 2
                && request.filters().warehouses().size() == 2), any());
    }

    @Test
    void rejectsBlankMultiFilterValues() throws Exception {
        mvc.perform(post("/api/v1/sales-reports/export")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportKey":"salesReport.invoices", "filters":{"payments":[" "]},
                                 "columns":[{"key":"invoice","label":"Factura"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    private String requestBody() {
        return """
                {
                  "reportKey": "salesReport.tickets",
                  "filters": {},
                  "search": "",
                  "columns": [{"key": "date", "label": "Fecha"}]
                }
                """;
    }

    private String ticketRequestBody(String dateFrom, String dateTo) {
        return """
                {
                  "reportKey": "salesReport.tickets",
                  "filters": {"dateFrom": "%s", "dateTo": "%s"},
                  "search": "",
                  "columns": [{"key": "date", "label": "Fecha"}]
                }
                """.formatted(dateFrom, dateTo);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
