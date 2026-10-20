package com.tpverp.backend.terminal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.document.template.OperationalReceiptJasperRenderer;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PrinterTestController.class)
@Import(PrinterTestControllerTest.MethodSecurityConfiguration.class)
class PrinterTestControllerTest {

    private static final String PATH = "/api/v1/hardware/printer-test";
    private static final UUID STORE_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final byte[] PDF = { 1, 2, 3, 4 };
    private static final byte[] PNG = { 5, 6, 7, 8 };

    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private OperationalReceiptJasperRenderer renderer;

    @Test
    void returnsRenderedPdfAndTicketImageForTerminalConfigurationPermission() throws Exception {
        givenCurrentStoreAndRenderedAssets();

        mvc.perform(get(PATH)
                        .with(user("cashier").authorities(() -> "CONFIGURACION_TERMINAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedPdf.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.renderedPdf.base64").value(Base64.getEncoder().encodeToString(PDF)))
                .andExpect(jsonPath("$.ticketRenderedImage.contentType").value("image/png"))
                .andExpect(jsonPath("$.ticketRenderedImage.base64").value(Base64.getEncoder().encodeToString(PNG)));

        verify(organization).currentStore();
        verify(renderer).renderPrinterTest(STORE_ID);
    }

    @Test
    void allowsAdminAndUsesOnlyTheCurrentStoreEvenIfAStoreParameterIsSupplied() throws Exception {
        givenCurrentStoreAndRenderedAssets();

        mvc.perform(get(PATH)
                        .with(user("admin").roles("ADMIN"))
                        .param("storeId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedPdf.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.ticketRenderedImage.contentType").value("image/png"));

        verify(organization).currentStore();
        verify(renderer).renderPrinterTest(STORE_ID);
    }

    @Test
    void deniesSalesPermissionWithoutRendering() throws Exception {
        mvc.perform(get(PATH).with(user("sales").authorities(() -> "VENTA")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(organization, renderer);
    }

    @Test
    void rejectsAnonymousRequestsWithoutRendering() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        verifyNoInteractions(organization, renderer);
    }

    private void givenCurrentStoreAndRenderedAssets() {
        var store = mock(Store.class);
        when(store.getId()).thenReturn(STORE_ID);
        when(organization.currentStore()).thenReturn(store);
        when(renderer.renderPrinterTest(STORE_ID))
                .thenReturn(new OperationalReceiptJasperRenderer.RenderedReceipt(PDF, PNG));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
