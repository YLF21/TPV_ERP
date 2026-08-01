package com.tpverp.backend.control;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ControlAlertAnalyticsController.class)
@Import(ControlAlertAnalyticsControllerTest.MethodSecurityConfiguration.class)
class ControlAlertAnalyticsControllerTest {

    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired private MockMvc mvc;
    @MockitoBean private ControlAlertAnalyticsService service;

    @Test
    void exposesAnalyticsContractToAlertReaders() throws Exception {
        var result = new ControlAlertAnalyticsService.AnalyticsView(
                12, 3, FROM, TO,
                List.of(new ControlAlertAnalyticsService.KeyMetric("NEW", 7)),
                List.of(new ControlAlertAnalyticsService.KeyMetric("TICKET_CANCELLED", 4)),
                List.of(new ControlAlertAnalyticsService.LabeledMetric("u-1", "CAJERO", 6)),
                List.of(new ControlAlertAnalyticsService.LabeledMetric("t-1", "Terminal 1", 8)),
                List.of());
        when(service.analytics(FROM, TO, 48)).thenReturn(result);

        mvc.perform(get("/api/v1/control/alerts/analytics")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("overdueHours", "48")
                        .with(user("manager").authorities(
                                () -> "APP_GESTION_ACCESS", () -> "CONTROL_ALERTS_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.overdueCount").value(3))
                .andExpect(jsonPath("$.byStatus[0].key").value("NEW"))
                .andExpect(jsonPath("$.byStatus[0].count").value(7))
                .andExpect(jsonPath("$.byUser[0].label").value("CAJERO"));
        verify(service).analytics(FROM, TO, 48);
    }

    @Test
    void usesDefaultOverdueHoursAndRequiresBounds() throws Exception {
        when(service.analytics(FROM, TO, 24)).thenReturn(
                new ControlAlertAnalyticsService.AnalyticsView(
                        0, 0, FROM, TO, List.of(), List.of(), List.of(), List.of(), List.of()));

        mvc.perform(get("/api/v1/control/alerts/analytics")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        verify(service).analytics(FROM, TO, 24);

        mvc.perform(get("/api/v1/control/alerts/analytics")
                        .param("from", FROM.toString())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUsersWithoutAlertPermissions() throws Exception {
        mvc.perform(get("/api/v1/control/alerts/analytics")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(user("sales").authorities(() -> "APP_GESTION_ACCESS")))
                .andExpect(status().isForbidden());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
