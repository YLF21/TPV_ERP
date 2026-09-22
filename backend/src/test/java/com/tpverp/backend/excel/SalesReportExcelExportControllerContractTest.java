package com.tpverp.backend.excel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
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
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
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
                .andExpect(status().isOk());
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

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
