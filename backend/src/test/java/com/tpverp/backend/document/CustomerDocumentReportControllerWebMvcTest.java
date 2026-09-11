package com.tpverp.backend.document;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.shared.api.PagedResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({TicketReportController.class, DocumentReportController.class})
@Import(CustomerDocumentReportControllerWebMvcTest.MethodSecurityConfiguration.class)
class CustomerDocumentReportControllerWebMvcTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String ROOT = "/api/v1/document-reports/";
    private static final CustomerDocumentReportFilter EMPTY_FILTER =
            new CustomerDocumentReportFilter(null, null, null, null, null, null);

    @Autowired private MockMvc mvc;
    @MockitoBean private TicketReportService tickets;
    @MockitoBean private DocumentReportService documents;

    @Test
    void bindsCustomerAndCursorWithoutChangingPageContract() throws Exception {
        when(tickets.list(50, "next", CUSTOMER_ID, EMPTY_FILTER))
                .thenReturn(new PagedResult<>(List.of(), null, false));
        when(documents.listInvoices(50, "next", true, false, CUSTOMER_ID, EMPTY_FILTER))
                .thenReturn(new PagedResult<>(List.of(), null, false));
        when(documents.listDeliveryNotes(50, "next", true, false, CUSTOMER_ID, EMPTY_FILTER))
                .thenReturn(new PagedResult<>(List.of(), null, false));

        for (var path : List.of("tickets", "invoices", "delivery-notes")) {
            mvc.perform(get(ROOT + path).param("customerId", CUSTOMER_ID.toString())
                            .param("limit", "50").param("cursor", "next")
                            .with(user("sale").authorities(() -> "VENTA")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.hasMore").value(false));
        }
        verify(tickets).list(50, "next", CUSTOMER_ID, EMPTY_FILTER);
        verify(documents).listInvoices(50, "next", true, false, CUSTOMER_ID, EMPTY_FILTER);
        verify(documents).listDeliveryNotes(50, "next", true, false, CUSTOMER_ID, EMPTY_FILTER);
    }

    @Test
    void keepsUnfilteredRequestsCompatible() throws Exception {
        for (var path : List.of("tickets", "invoices", "delivery-notes")) {
            mvc.perform(get(ROOT + path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk());
        }
        verify(tickets).list(null, null, null, EMPTY_FILTER);
        verify(documents).listInvoices(null, null, true, false, null, EMPTY_FILTER);
        verify(documents).listDeliveryNotes(null, null, true, false, null, EMPTY_FILTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid-customer"})
    void rejectsProvidedInvalidCustomersInsteadOfDroppingTheFilter(String customerId) throws Exception {
        for (var path : List.of("tickets", "invoices", "delivery-notes")) {
            mvc.perform(get(ROOT + path).param("customerId", customerId)
                            .with(user("sale").authorities(() -> "VENTA")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(tickets, documents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMERS_READ", "GESTION_CLIENTE_PROVEEDOR"})
    void customerDirectoryPermissionsDoNotGrantDocumentAccess(String permission) throws Exception {
        for (var path : List.of("tickets", "invoices", "delivery-notes")) {
            mvc.perform(get(ROOT + path).param("customerId", CUSTOMER_ID.toString())
                            .with(user("customer-reader").authorities(() -> permission)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(tickets, documents);
    }

    @Test
    void keepsGranularDocumentPermissionsAndAccountingScope() throws Exception {
        mvc.perform(get(ROOT + "tickets").param("customerId", CUSTOMER_ID.toString())
                        .with(user("ticket-reader").authorities(() -> "TICKETS_READ")))
                .andExpect(status().isOk());
        mvc.perform(get(ROOT + "invoices").param("customerId", CUSTOMER_ID.toString())
                        .with(user("invoice-reader").authorities(() -> "INVOICES_READ")))
                .andExpect(status().isOk());
        mvc.perform(get(ROOT + "delivery-notes").param("customerId", CUSTOMER_ID.toString())
                        .with(user("delivery-reader").authorities(() -> "DELIVERY_NOTES_READ")))
                .andExpect(status().isOk());
        mvc.perform(get(ROOT + "invoices").param("customerId", CUSTOMER_ID.toString())
                        .with(user("ticket-reader").authorities(() -> "TICKETS_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ROOT + "invoices").param("customerId", CUSTOMER_ID.toString())
                        .with(user("accounting").authorities(() -> "GESTION_CUENTAS")))
                .andExpect(status().isOk());

        verify(tickets).list(null, null, CUSTOMER_ID, EMPTY_FILTER);
        verify(documents).listInvoices(null, null, true, false, CUSTOMER_ID, EMPTY_FILTER);
        verify(documents).listDeliveryNotes(null, null, true, false, CUSTOMER_ID, EMPTY_FILTER);
        verify(documents).listInvoices(null, null, false, false, CUSTOMER_ID, EMPTY_FILTER);
    }

    @Test
    void bindsSearchStatusDateRangeAndOrderForEveryDocumentTab() throws Exception {
        var filter = new CustomerDocumentReportFilter("FV-2", DocumentStatus.PAGADO,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 9), "total", "asc");
        for (var path : List.of("tickets", "invoices", "delivery-notes")) {
            mvc.perform(get(ROOT + path).param("customerId", CUSTOMER_ID.toString())
                            .param("search", " FV-2 ").param("status", "PAGADO")
                            .param("dateFrom", "2026-09-01").param("dateTo", "2026-09-09")
                            .param("sortBy", "total").param("sortDirection", "asc")
                            .with(user("sale").authorities(() -> "VENTA")))
                    .andExpect(status().isOk());
        }
        verify(tickets).list(null, null, CUSTOMER_ID, filter);
        verify(documents).listInvoices(null, null, true, false, CUSTOMER_ID, filter);
        verify(documents).listDeliveryNotes(null, null, true, false, CUSTOMER_ID, filter);
    }

    @ParameterizedTest
    @CsvSource({"status,INVALID", "dateFrom,invalid", "sortBy,total desc; drop table documento",
            "sortDirection,sideways"})
    void rejectsInvalidFilterInputs(String parameter, String value) throws Exception {
        mvc.perform(get(ROOT + "tickets").param("customerId", CUSTOMER_ID.toString())
                        .param(parameter, value).with(user("sale").authorities(() -> "VENTA")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tickets, documents);
    }

    @Test
    void rejectsAnInvertedDateRange() throws Exception {
        mvc.perform(get(ROOT + "tickets").param("customerId", CUSTOMER_ID.toString())
                        .param("dateFrom", "2026-09-09").param("dateTo", "2026-09-01")
                        .with(user("sale").authorities(() -> "VENTA")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(tickets, documents);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(ROOT + "tickets").param("customerId", CUSTOMER_ID.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tickets, documents);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
