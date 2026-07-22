package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;

@WebMvcTest(VerifactuPosController.class)
@Import(VerifactuPosControllerTest.MethodSecurityConfiguration.class)
class VerifactuPosControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private VerifactuPosService service;

    @Test
    void exposesOnlyTerminalScopedReadEndpointsForSaleAndAdmin() throws Exception {
        assertThat(VerifactuPosController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/pos");
        assertAuthorization("status", Authentication.class);
        assertAuthorization("queue", int.class, Authentication.class);

        when(service.status(any())).thenReturn(new VerifactuPosStatusView(
                true, VerifactuPosPresentationStatus.OPERATIVO, 0, 0, 0));
        when(service.queue(org.mockito.ArgumentMatchers.eq(50), any()))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/verifactu/pos/status")
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presentationStatus").value("OPERATIVO"));
        mvc.perform(get("/api/v1/verifactu/pos/queue")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void unrelatedSalesManagementPermissionCannotUsePosEndpoints() throws Exception {
        mvc.perform(get("/api/v1/verifactu/pos/status")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/verifactu/pos/queue")
                        .with(user("manager").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void fabricatedScopeParametersAreIgnoredAndNeverReachTheService() throws Exception {
        var fabricatedTerminal = UUID.randomUUID();
        when(service.queue(org.mockito.ArgumentMatchers.eq(7), any()))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/verifactu/pos/queue")
                        .param("limit", "7")
                        .param("terminalId", fabricatedTerminal.toString())
                        .param("tiendaId", UUID.randomUUID().toString())
                        .param("empresaId", UUID.randomUUID().toString())
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isOk());

        verify(service).queue(org.mockito.ArgumentMatchers.eq(7), any(Authentication.class));
    }

    @Test
    void queueResponseIsSanitizedByContract() throws Exception {
        when(service.queue(org.mockito.ArgumentMatchers.eq(50), any()))
                .thenReturn(List.of(new VerifactuPosQueueItem(
                        "T-1", FiscalDocumentType.F2,
                        FiscalSubmissionStatus.RECHAZADO,
                        Instant.parse("2026-07-21T12:00:00Z"),
                        "VERIFACTU_REVIEW_REQUIRED")));

        mvc.perform(get("/api/v1/verifactu/pos/queue")
                        .with(user("seller").authorities(() -> "VENTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentNumber").value("T-1"))
                .andExpect(jsonPath("$[0].operationalMessageCode")
                        .value("VERIFACTU_REVIEW_REQUIRED"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("xml"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("responsePayload"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("certificate"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("customer"))));
    }

    private static void assertAuthorization(String methodName, Class<?>... parameters)
            throws NoSuchMethodException {
        var method = VerifactuPosController.class.getDeclaredMethod(methodName, parameters);
        var authorization = method.getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).contains("hasRole('ADMIN')", "hasAuthority('VENTA')");
        assertThat(method.getParameterTypes()).doesNotContain(UUID.class);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
