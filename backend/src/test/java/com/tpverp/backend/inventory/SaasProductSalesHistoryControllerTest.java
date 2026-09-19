package com.tpverp.backend.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.document.template.RenderedDocumentView.RenderedArtifact;
import com.tpverp.backend.inventory.SaasProductSalesHistoryApi.PdfResponse;
import com.tpverp.backend.shared.api.JacksonConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SaasProductSalesHistoryController.class)
@Import({SaasProductSalesHistoryControllerTest.MethodSecurity.class, JacksonConfiguration.class})
class SaasProductSalesHistoryControllerTest {
    private static final UUID PRODUCT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID STORE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String PATH = "/api/v1/stock/products/" + PRODUCT + "/sales-history/saas";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 19);
    private static final String EXPORT_BODY = """
            {
              "from": "2026-09-01", "to": "2026-09-19", "status": "CONFIRMADO",
              "storeIds": ["22222222-2222-4222-8222-222222222222"],
              "sortBy": "quantity", "sortDirection": "desc", "view": "detail", "locale": "es",
              "columns": [{"key": "quantity", "label": "Cantidad"}],
              "labels": {
                "title": "Historial", "product": "Producto", "code": "Código", "period": "Período",
                "status": "Estado", "allStatuses": "Todos", "totalQuantity": "Cantidad neta", "totalAmount": "Importe neto"
              }
            }
            """;

    @Autowired private MockMvc mvc;
    @MockitoBean private SaasProductSalesHistoryService service;

    @ParameterizedTest
    @CsvSource({"page,VENTA", "export,VENTA", "render,VENTA",
            "page,STOCK_READ", "export,STOCK_READ", "render,STOCK_READ"})
    void permitsSalesAndStockReadersForEveryEndpoint(String endpoint, String authority) throws Exception {
        var bytes = new byte[]{1, 2, 3};
        switch (endpoint) {
            case "page" -> when(service.page(eq(PRODUCT), any(), eq(200), eq("opaque-cursor")))
                    .thenReturn(SaasProductSalesHistoryTestData.response());
            case "export" -> when(service.excel(eq(PRODUCT), any())).thenReturn(bytes);
            case "render" -> when(service.pdf(eq(PRODUCT), any())).thenReturn(
                    new PdfResponse(new RenderedArtifact("application/pdf", "JVBERi0="), "historial.pdf"));
            default -> throw new IllegalArgumentException(endpoint);
        }

        var result = mvc.perform(request(endpoint).with(user("operator").authorities(() -> authority)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));

        switch (endpoint) {
            case "page" -> {
                result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.coverage").value("RECEIVED_IN_SAAS"))
                        .andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.stores").isArray())
                        .andExpect(jsonPath("$.totals").isArray()).andExpect(jsonPath("$.comparison").isArray())
                        .andExpect(jsonPath("$.hasMore").value(false))
                        .andExpect(jsonPath("$.items[0].quantity").isString()).andExpect(jsonPath("$.items[0].quantity").value("2"))
                        .andExpect(jsonPath("$.items[0].unitPrice").isString()).andExpect(jsonPath("$.items[0].unitPrice").value("1.875"))
                        .andExpect(jsonPath("$.items[0].lineTotal").isString()).andExpect(jsonPath("$.items[0].lineTotal").value("3.75"))
                        .andExpect(jsonPath("$.totals[0].netAmount").isString()).andExpect(jsonPath("$.totals[0].netAmount").value("3.75"))
                        .andExpect(jsonPath("$.comparison[0].netQuantity").isString());
                verify(service).page(eq(PRODUCT), argThat(filters -> filters.from().equals(FROM)
                        && filters.to().equals(TO) && filters.storeIds().equals(List.of(STORE))
                        && "CONFIRMADO".equals(filters.status()) && "quantity".equals(filters.sortBy())
                        && "desc".equals(filters.sortDirection())), eq(200), eq("opaque-cursor"));
            }
            case "export" -> {
                result.andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .andExpect(content().bytes(bytes));
                verify(service).excel(eq(PRODUCT), argThat(payload -> payload.storeIds().equals(List.of(STORE))
                        && payload.from().equals(FROM) && payload.to().equals(TO) && "detail".equals(payload.view())));
            }
            case "render" -> {
                result.andExpect(jsonPath("$.renderedPdf.contentType").value("application/pdf"))
                        .andExpect(jsonPath("$.renderedPdf.base64").value("JVBERi0="));
                verify(service).pdf(eq(PRODUCT), argThat(payload -> payload.storeIds().equals(List.of(STORE))
                        && payload.from().equals(FROM) && payload.to().equals(TO) && "detail".equals(payload.view())));
            }
            default -> throw new IllegalArgumentException(endpoint);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "export", "render"})
    void deniesAuthenticatedUsersWithoutSalesOrStockAuthorityBeforeCallingService(String endpoint) throws Exception {
        mvc.perform(request(endpoint).with(user("customer-reader").authorities(() -> "CUSTOMERS_READ")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "export", "render"})
    void requiresAuthenticationForEveryEndpoint(String endpoint) throws Exception {
        mvc.perform(request(endpoint)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder request(String endpoint) {
        if ("page".equals(endpoint)) {
            return get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                    .param("status", "CONFIRMADO").param("storeIds", STORE.toString())
                    .param("sortBy", "quantity").param("sortDirection", "desc")
                    .param("size", "200").param("cursor", "opaque-cursor");
        }
        // A valid CSRF token and body ensure a 403 is from authorization, not request validation or CSRF.
        return post(PATH + "/" + endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(EXPORT_BODY);
    }

    @EnableMethodSecurity
    static class MethodSecurity { }
}
