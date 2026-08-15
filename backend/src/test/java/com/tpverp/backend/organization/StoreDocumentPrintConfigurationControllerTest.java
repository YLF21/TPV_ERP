package com.tpverp.backend.organization;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StoreDocumentPrintConfigurationController.class)
@Import(StoreDocumentPrintConfigurationControllerTest.MethodSecurityConfiguration.class)
class StoreDocumentPrintConfigurationControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private StoreDocumentPrintConfigurationService service;

    @Test
    void returnsOnlyTheCurrentStoreConfigurationToAdministrators() throws Exception {
        var storeId = UUID.randomUUID();
        when(service.configuration()).thenReturn(
                new StoreDocumentPrintConfigurationService.Configuration(
                        storeId, null, "Ticket", "Factura", "Albaran", "Vale",
                        TicketPrintStyle.PRINCIPAL));

        mvc.perform(get("/api/v1/store-document-print-configuration")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.ticketObservations").value("Ticket"))
                .andExpect(jsonPath("$.invoiceObservations").value("Factura"))
                .andExpect(jsonPath("$.deliveryNoteObservations").value("Albaran"))
                .andExpect(jsonPath("$.voucherObservations").value("Vale"))
                .andExpect(jsonPath("$.ticketStyle").value("PRINCIPAL"));
    }

    @Test
    void administratorCanSelectTheCompactTicketStyle() throws Exception {
        var storeId = UUID.randomUUID();
        when(service.updateTicketStyle(TicketPrintStyle.COMPACTA)).thenReturn(
                new StoreDocumentPrintConfigurationService.Configuration(
                        storeId, null, null, null, null, null,
                        TicketPrintStyle.COMPACTA));

        mvc.perform(put("/api/v1/store-document-print-configuration/ticket-style")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"style\":\"COMPACTA\"}")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketStyle").value("COMPACTA"));
    }

    @Test
    void rejectsNonAdministrators() throws Exception {
        mvc.perform(get("/api/v1/store-document-print-configuration")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
