package com.tpverp.backend.document;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.template.TicketJasperRenderer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
class TicketControllerJasperContractTest {

    @Autowired MockMvc mvc;

    @MockitoBean DocumentService service;
    @MockitoBean DocumentFiscalQrService fiscalQr;
    @MockitoBean DocumentViewAssembler views;
    @MockitoBean TicketReturnService returns;
    @MockitoBean TicketCancellationService cancellations;
    @MockitoBean GenericSalesApiService genericSales;
    @MockitoBean PreviousTicketImportService previousTicketImports;
    @MockitoBean TicketJasperRenderer jasperRenderer;

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsAnInlinePdfUsingTheRequestedBuiltInTemplate() throws Exception {
        UUID id = UUID.randomUUID();
        var ticket = mock(CommercialDocument.class);
        when(ticket.getNumero()).thenReturn("T/2026 42");
        when(service.loadForPrint(id)).thenReturn(ticket);
        when(jasperRenderer.render(ticket, TicketJasperRenderer.Template.MINIMALISTA))
                .thenReturn("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        mvc.perform(get("/api/v1/tickets/{id}/pdf", id)
                        .param("template", "minimalista"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"ticket-T_2026_42.pdf\""))
                .andExpect(content().bytes(
                        "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

        verify(jasperRenderer).render(
                ticket, TicketJasperRenderer.Template.MINIMALISTA);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsUnknownTemplateBeforeRendering() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.loadForPrint(id)).thenReturn(mock(CommercialDocument.class));

        mvc.perform(get("/api/v1/tickets/{id}/pdf", id)
                        .param("template", "unknown"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void usesTheStoreSelectedTemplateWhenTheRequestHasNoOverride() throws Exception {
        UUID id = UUID.randomUUID();
        var ticket = mock(CommercialDocument.class);
        when(service.loadForPrint(id)).thenReturn(ticket);
        when(jasperRenderer.selectedTemplate())
                .thenReturn(TicketJasperRenderer.Template.COMPACTA);
        when(jasperRenderer.render(ticket, TicketJasperRenderer.Template.COMPACTA))
                .thenReturn("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        mvc.perform(get("/api/v1/tickets/{id}/pdf", id))
                .andExpect(status().isOk());

        verify(jasperRenderer).render(ticket, TicketJasperRenderer.Template.COMPACTA);
    }
}
