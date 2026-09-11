package com.tpverp.backend.document;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SaasCustomerDocumentController.class)
@Import(SaasCustomerDocumentControllerTest.MethodSecurity.class)
class SaasCustomerDocumentControllerTest {
    final UUID customer = UUID.randomUUID();
    final String prefix = "/api/v1/customer-document-reports/saas/";
    @Autowired MockMvc mvc;
    @MockitoBean SaasCustomerDocumentService service;

    @ParameterizedTest @CsvSource({"tickets,TICKETS_READ", "invoices,INVOICES_READ", "delivery-notes,DELIVERY_NOTES_READ"})
    void tabSpecificAuthorityReadsOnlyThatTab(String report, String permission) throws Exception {
        var page = new SaasCustomerDocumentApi.Page(customer, UUID.randomUUID(),
                new SaasCustomerDocumentApi.CustomerProfile(UUID.randomUUID(), "C", "Name", "TAX", ""),
                List.of(), null, false, SaasCustomerDocumentApi.COVERAGE);
        when(service.page(eq(customer), eq(report), any(), any(), any(), anyInt(), any(), any())).thenReturn(page);
        mvc.perform(get(prefix + customer + "/" + report).with(user("operator").authorities(() -> permission)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.localCustomerId").value(customer.toString()))
                .andExpect(jsonPath("$.coverage").value("RECEIVED_V2_ONLY"));
    }
    @Test void wrongTabAndUnauthenticatedRequestsNeverReachService() throws Exception {
        mvc.perform(get(prefix + customer + "/invoices").with(user("operator").authorities(() -> "TICKETS_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get(prefix + customer + "/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get(prefix + customer + "/annual.pdf").param("year", "2026")
                        .with(user("operator").authorities(() -> "TICKETS_READ"))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void annualSpecificRouteReturnsPdfWithoutTreatingItAsATab() throws Exception {
        when(service.annual(eq(customer), eq(2026), eq("es"), any())).thenReturn(new byte[]{1, 2});
        mvc.perform(get(prefix + customer + "/annual.pdf").param("year", "2026")
                        .with(user("operator").authorities(() -> "INVOICES_READ")))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).annual(eq(customer), eq(2026), eq("es"), any());
    }
    @Test void safeErrorsExposeOnlyLocalCodeAndNeverBecomeEmptySuccess() throws Exception {
        when(service.page(any(), any(), any(), any(), any(), anyInt(), any(), any())).thenThrow(SaasCustomerDocumentException.binding());
        mvc.perform(get(prefix + customer + "/tickets").with(user("operator").authorities(() -> "TICKETS_READ")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAAS_CUSTOMER_BINDING_REQUIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    @EnableMethodSecurity static class MethodSecurity { }
}
