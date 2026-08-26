package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.tpverp.backend.organization.CurrentOrganization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(FiscalController.class)
@Import(FiscalControllerAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class FiscalControllerAuthorizationWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FiscalModeTransitionService modes;
    @MockitoBean
    private FiscalEventService events;
    @MockitoBean
    private FiscalIntegrityService integrity;
    @MockitoBean
    private FiscalExportService exports;
    @MockitoBean
    private FiscalRequiredSubmissionService requiredSubmissions;
    @MockitoBean
    private FiscalExportJobService fiscalJobs;
    @MockitoBean
    private FiscalExportJobLauncher fiscalLauncher;
    @MockitoBean
    private FiscalIntegrityJobService integrityJobs;
    @MockitoBean
    private FiscalIntegrityJobLauncher integrityLauncher;
    @MockitoBean
    private CurrentOrganization organization;

    @Test
    void fiscalReaderWithGestionAccessCanReadStatusAndSanitizedEvents() throws Exception {
        var companyId = UUID.randomUUID();
        when(modes.status()).thenReturn(statusView(companyId));
        var fiscalEvent = new FiscalEvent(companyId, UUID.randomUUID(), UUID.randomUUID(), 1,
                FiscalEventType.START_NO_VERIFACTU, FiscalMode.NO_VERIFACTU,
                Instant.parse("2026-08-26T09:00:00Z"), null, "EVENT-HASH",
                "<unsigned>secret</unsigned>", "<signed>secret</signed>",
                "XML-HASH", Instant.parse("2026-08-26T09:00:01Z"));
        when(events.findTop50ViewsCurrent(organization))
                .thenReturn(List.of(FiscalEventView.from(fiscalEvent)));

        mvc.perform(get("/api/v1/fiscal/status").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NO_VERIFACTU"));
        mvc.perform(get("/api/v1/fiscal/events").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].hash").value("EVENT-HASH"))
                .andExpect(jsonPath("$[0].signed").value(true))
                .andExpect(jsonPath("$[0].unsignedXml").doesNotExist())
                .andExpect(jsonPath("$[0].signedXml").doesNotExist());
    }

    @Test
    void fiscalReadPermissionWithoutGestionAccessIsRejected() throws Exception {
        mvc.perform(get("/api/v1/fiscal/status")
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/events")
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void sideEffectingIntegrityAndExportRequireFiscalManage() throws Exception {
        when(integrity.check()).thenReturn(new FiscalIntegrityCheckView(
                Instant.parse("2026-08-26T10:00:00Z"), FiscalMode.NO_VERIFACTU,
                true, List.of(), 3, 2));
        when(exports.export(FiscalExportKind.BILLING, null, null))
                .thenReturn(new FiscalExportView(
                        UUID.randomUUID(), FiscalExportKind.BILLING,
                        Instant.parse("2026-08-26T10:00:00Z"), null, null,
                        0, null, List.of()));

        mvc.perform(post("/api/v1/fiscal/integrity-checks")
                        .with(reader()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fiscal/exports")
                        .with(reader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BILLING\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/fiscal/integrity-checks")
                        .with(manager()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/v1/fiscal/exports")
                        .with(manager()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BILLING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("BILLING"));

        mvc.perform(post("/api/v1/fiscal/exports/download")
                        .with(reader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BILLING\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fiscal/exports/download")
                        .with(manager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BILLING\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("application/zip")));
    }

    private static FiscalStatusView statusView(UUID companyId) {
        return new FiscalStatusView(companyId, FiscalMode.NO_VERIFACTU, 2,
                Instant.parse("2026-08-26T09:00:00Z"), FiscalRuntimeClass.SANDBOX,
                FiscalEndpointEnvironment.TEST, FiscalTransportMode.SIMULATED,
                false, null, null);
    }

    private static RequestPostProcessor reader() {
        return permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ");
    }

    private static RequestPostProcessor manager() {
        return permissions("manager", "APP_GESTION_ACCESS", "VERIFACTU_READ",
                "VERIFACTU_MANAGE");
    }

    private static RequestPostProcessor permissions(String username, String... values) {
        return user(username).authorities(Arrays.stream(values)
                .map(SimpleGrantedAuthority::new)
                .toList());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
