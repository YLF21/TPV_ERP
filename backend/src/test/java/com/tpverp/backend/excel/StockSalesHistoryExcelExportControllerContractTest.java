package com.tpverp.backend.excel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StockSalesHistoryExcelExportController.class)
@Import(StockSalesHistoryExcelExportControllerContractTest.MethodSecurityConfiguration.class)
class StockSalesHistoryExcelExportControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private StockSalesHistoryExcelExportService service;

    @Test
    void posUserWithHistoryAccessCanExportTheWorkbook() throws Exception {
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});

        mvc.perform(post("/api/v1/stock/products/{productId}/sales-history/export", UUID.randomUUID())
                        .with(user("seller").authorities(() -> "VENTA"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void userWithoutHistoryAccessCannotExport() throws Exception {
        mvc.perform(post("/api/v1/stock/products/{productId}/sales-history/export", UUID.randomUUID())
                        .with(user("customer-only").authorities(() -> "CUSTOMER_READ"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isForbidden());
    }

    private static String requestBody() {
        return """
                {
                  "from": "2026-07-01",
                  "to": "2026-07-31",
                  "labels": {
                    "title": "Historial de ventas",
                    "product": "Producto",
                    "code": "Codigo",
                    "period": "Periodo",
                    "status": "Estado",
                    "allStatuses": "Todos",
                    "totalQuantity": "Cantidad total vendida",
                    "totalAmount": "Importe total"
                  },
                  "columns": [{"key": "quantity", "label": "Cantidad"}]
                }
                """;
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
