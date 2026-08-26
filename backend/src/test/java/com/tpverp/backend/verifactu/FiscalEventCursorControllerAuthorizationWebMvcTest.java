package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FiscalController.class)
@Import(FiscalEventCursorControllerAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class FiscalEventCursorControllerAuthorizationWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private FiscalModeTransitionService modes;
    @MockitoBean private FiscalEventService events;
    @MockitoBean private FiscalIntegrityService integrity;
    @MockitoBean private FiscalExportService exports;
    @MockitoBean private FiscalRequiredSubmissionService requiredSubmissions;
    @MockitoBean private FiscalExportJobService fiscalJobs;
    @MockitoBean private FiscalExportJobLauncher fiscalLauncher;
    @MockitoBean private FiscalIntegrityJobService integrityJobs;
    @MockitoBean private FiscalIntegrityJobLauncher integrityLauncher;
    @MockitoBean private com.tpverp.backend.organization.CurrentOrganization organization;

    @Test
    void readerCanUseKeysetEventsWithoutTotals() throws Exception {
        when(events.findCursorViewsCurrent(organization, 25, null)).thenReturn(
                new FiscalEventReadCursorPage(List.of(), 25, null, null, false, false, 0));

        mvc.perform(get("/api/v1/fiscal/events/cursor")
                        .queryParam("size", "25").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.snapshotSequence").value(0))
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    @Test
    void readPermissionWithoutGestionAccessIsRejected() throws Exception {
        mvc.perform(get("/api/v1/fiscal/events/cursor")
                        .with(user("reader").authorities(new SimpleGrantedAuthority("VERIFACTU_READ"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader() {
        return user("reader").authorities(Arrays.asList(
                new SimpleGrantedAuthority("APP_GESTION_ACCESS"),
                new SimpleGrantedAuthority("VERIFACTU_READ")));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
