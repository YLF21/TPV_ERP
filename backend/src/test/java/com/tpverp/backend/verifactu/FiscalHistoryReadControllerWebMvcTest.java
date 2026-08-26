package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(FiscalHistoryReadController.class)
@Import(FiscalHistoryReadControllerWebMvcTest.MethodSecurityConfiguration.class)
class FiscalHistoryReadControllerWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FiscalHistoryReadService history;

    @Test
    void fiscalReaderPuedeLeerAmbosHistorialesSinExponerXml() throws Exception {
        var exportId = UUID.randomUUID();
        when(history.exports(null)).thenReturn(List.of(new FiscalExportHistoryView(
                exportId, UUID.randomUUID(), UUID.randomUUID(), FiscalExportKind.BILLING,
                Instant.parse("2026-08-26T10:00:00Z"), null, null, 3, null,
                "A".repeat(64))));
        when(history.requiredSubmissions(null)).thenReturn(List.of(new FiscalRequiredSubmissionHistoryView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "REQ-1",
                Instant.parse("2026-08-26T10:00:00Z"), null, exportId, "PENDIENTE")));

        mvc.perform(get("/api/v1/fiscal/exports").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exportId").value(exportId.toString()))
                .andExpect(jsonPath("$[0].contentHash").value("A".repeat(64)))
                .andExpect(jsonPath("$[0].xml").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("signedXml"))));
        mvc.perform(get("/api/v1/fiscal/required-submissions").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value("REQ-1"))
                .andExpect(jsonPath("$[0].status").value("PENDIENTE"));
    }

    @Test
    void exigePermisoDeGestionYLectura() throws Exception {
        mvc.perform(get("/api/v1/fiscal/exports")
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/required-submissions")
                        .with(permissions("user", "APP_GESTION_ACCESS")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/exports").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void reenviaElLimiteAlServicioYElServicioLoValida() throws Exception {
        when(history.exports(25)).thenReturn(List.of());
        mvc.perform(get("/api/v1/fiscal/exports?limit=25").with(reader()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        org.mockito.Mockito.verify(history).exports(25);
    }

    private static RequestPostProcessor reader() {
        return permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ");
    }

    private static RequestPostProcessor permissions(String username, String... values) {
        return user(username).authorities(Arrays.stream(values)
                .map(SimpleGrantedAuthority::new).toList());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
