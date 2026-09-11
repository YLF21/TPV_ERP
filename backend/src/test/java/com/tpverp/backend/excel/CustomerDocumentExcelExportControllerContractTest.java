package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerDocumentExcelExportController.class)
@Import(CustomerDocumentExcelExportControllerContractTest.MethodSecurityConfiguration.class)
class CustomerDocumentExcelExportControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private CustomerDocumentExcelExportService service;

    @ParameterizedTest
    @CsvSource({"tickets,TICKETS_READ", "invoices,INVOICES_READ", "delivery-notes,DELIVERY_NOTES_READ",
            "tickets,VENTA", "invoices,VENTA", "delivery-notes,VENTA", "tickets,GESTION_VENTAS",
            "invoices,GESTION_VENTAS", "delivery-notes,GESTION_VENTAS", "tickets,ROLE_ADMIN",
            "invoices,ROLE_ADMIN", "delivery-notes,ROLE_ADMIN"})
    void matchingSalesPermissionsCanExport(String reportKey, String authority) throws Exception {
        when(service.export(any(), any())).thenReturn(new byte[] {1, 2, 3});
        mvc.perform(post("/api/v1/customer-document-reports/export.xlsx")
                        .with(user("seller").authorities(() -> authority)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(reportKey)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"documentos-cliente.xlsx\""));
    }

    @ParameterizedTest
    @CsvSource({"tickets,INVOICES_READ", "tickets,DELIVERY_NOTES_READ", "invoices,TICKETS_READ",
            "invoices,DELIVERY_NOTES_READ", "delivery-notes,TICKETS_READ", "delivery-notes,INVOICES_READ",
            "tickets,GESTION_PRODUCTO", "invoices,GESTION_ALMACEN", "delivery-notes,GESTION_CUENTAS",
            "tickets,CUSTOMER_READ"})
    void unrelatedPermissionsCannotExport(String reportKey, String authority) throws Exception {
        mvc.perform(post("/api/v1/customer-document-reports/export.xlsx")
                        .with(user("other").authorities(() -> authority)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(reportKey)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"en,The export exceeds 50", "zh,导出超过 50", "es,La exportación supera 50"})
    void returnsAnExplicitLocalizedLimitErrorWithoutWorkbook(String language, String detailPrefix) throws Exception {
        when(service.export(any(), any())).thenThrow(new CustomerDocumentExcelExportService.ExportLimitExceededException());
        mvc.perform(post("/api/v1/customer-document-reports/export.xlsx")
                        .with(user("seller").authorities(() -> "VENTA")).with(csrf())
                        .header("Accept-Language", language)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody("tickets")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("customer_documents_export_limit_exceeded"))
                .andExpect(jsonPath("$.maxRows").value(50_000))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(detailPrefix)));
    }

    @Test
    void rejectsMissingCustomerAndEmptyColumnSelection() throws Exception {
        var invalid = requestBody("tickets").replace("\"11111111-1111-1111-1111-111111111111\"", "null")
                .replace("[{\"key\":\"number\",\"label\":\"Número\"}]", "[]");
        mvc.perform(post("/api/v1/customer-document-reports/export.xlsx")
                        .with(user("seller").authorities(() -> "VENTA")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsLegacyLabelsAndDefaultsCustomerAndFilterLabels() throws Exception {
        when(service.export(any(), any())).thenReturn(new byte[] {1});
        mvc.perform(post("/api/v1/customer-document-reports/export.xlsx")
                        .with(user("seller").authorities(() -> "VENTA")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody("tickets")))
                .andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(CustomerDocumentExportRequest.class);
        verify(service).export(request.capture(), any());
        assertThat(request.getValue().labels().customerTaxId()).isEqualTo("NIF");
        assertThat(request.getValue().labels().grandTotal()).isEqualTo("Total documentos");
        assertThat(request.getValue().labels().filters().none()).isEqualTo("Sin filtros");
    }

    @Test
    void rejectsEmptySuppliedMetadataLabels() throws Exception {
        var invalid = requestBody("tickets").replace("\"sheetName\":", "\"customerName\":\"\",\"sheetName\":");
        mvc.perform(post("/api/v1/customer-document-reports/export.xlsx")
                        .with(user("seller").authorities(() -> "VENTA")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static String requestBody(String reportKey) {
        return """
                {
                  "customerId":"11111111-1111-1111-1111-111111111111",
                  "reportKey":"%s",
                  "documentIds":[],
                  "columns":[{"key":"number","label":"Número"}],
                  "labels":{
                    "sheetName":"Documentos del cliente",
                    "types":{"TICKET":"Ticket"},
                    "statuses":{"CONFIRMADO":"Confirmado"}
                  }
                }
                """.formatted(reportKey);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
