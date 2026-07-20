package com.tpverp.backend.excel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
