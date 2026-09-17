package com.tpverp.backend.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(DashboardPreferenceController.class)
@Import(DashboardPreferenceControllerTest.MethodSecurityConfiguration.class)
class DashboardPreferenceControllerTest {

    private static final String PATH = "/api/v1/gestion/dashboard/preference";
    private static final List<DashboardWidgetLayout> WIDGETS = List.of(
            new DashboardWidgetLayout("sales.trend", 12, 2));
    private static final DashboardOptions OPTIONS = DashboardOptions.defaults();
    private static final DashboardPreferenceService.SavePreferenceRequest REQUEST =
            new DashboardPreferenceService.SavePreferenceRequest(WIDGETS, OPTIONS);
    private static final String BODY = new ObjectMapper().writeValueAsString(REQUEST);
    private static final DashboardPreferenceService.PreferenceView VIEW =
            new DashboardPreferenceService.PreferenceView(WIDGETS, List.of("sales.trend"), OPTIONS,
                    LocalDate.of(2026, 7, 18), "Atlantic/Canary");

    @Autowired private MockMvc mvc;
    @MockitoBean private DashboardPreferenceService service;

    @ParameterizedTest
    @ValueSource(strings = {"APP_GESTION_ACCESS", "ROLE_ADMIN"})
    void appUsersAndAdministratorsCanReadAndSaveTheirOwnPreference(String authority) throws Exception {
        var requestUser = user("dashboard-reader").authorities(new SimpleGrantedAuthority(authority));
        when(service.get(any(Authentication.class))).thenReturn(VIEW);
        when(service.save(eq(REQUEST), any(Authentication.class))).thenReturn(VIEW);

        mvc.perform(get(PATH).with(requestUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeTimezone").value("Atlantic/Canary"))
                .andExpect(jsonPath("$.businessDate").value("2026-07-18"))
                .andExpect(jsonPath("$.options.defaultPeriod").value("MONTH"));
        mvc.perform(put(PATH).with(requestUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options.trendDisplay").value("LINE"));

        verify(service).get(argThat(auth -> auth.getName().equals("dashboard-reader")));
        verify(service).save(eq(REQUEST), argThat(auth -> auth.getName().equals("dashboard-reader")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GESTION_VENTAS", "CONTROL_ALERTS_READ", "ROLE_USER"})
    void requiresAppAccessBeforeInvokingThePreferenceService(String authority) throws Exception {
        var requestUser = user("other").authorities(new SimpleGrantedAuthority(authority));

        mvc.perform(get(PATH).with(requestUser)).andExpect(status().isForbidden());
        mvc.perform(put(PATH).with(requestUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsAnOlderClientBodyWithoutOptions() throws Exception {
        when(service.save(any(), any())).thenReturn(VIEW);

        mvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"widgets\":[{\"key\":\"sales.trend\",\"width\":12,\"height\":2}]}"))
                .andExpect(status().isOk());

        verify(service).save(eq(new DashboardPreferenceService.SavePreferenceRequest(WIDGETS)),
                any(Authentication.class));
    }

    @Test
    void rejectsInvalidOptionsAndMissingWidgetsDuringRequestBinding() throws Exception {
        for (var body : new String[]{"null", "{}", "{\"widgets\":null}",
                BODY.replace("\"MONTH\"", "\"ALL_TIME\""),
                BODY.replace("\"LINE\"", "\"PIE\""),
                BODY.replace("\"BAR\"", "\"LINE\""),
                BODY.replace("\"COMFORTABLE\"", "\"DENSE\""),
                BODY.replace("\"showComparison\":true", "\"showComparison\":null")}) {
            mvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en", "zh"})
    void reportsConcurrentSavesAsALocalizedConflictWithoutExposingPersistenceDetails(String language) throws Exception {
        when(service.save(any(), any()))
                .thenThrow(new IllegalStateException("message.dashboard.preference_conflict"));
        var expected = switch (language) {
            case "en" -> "Another session saved the overview configuration at the same time. Your changes were not saved; review the current configuration before saving again.";
            case "zh" -> "另一会话同时保存了概览配置。您的更改尚未保存；请查看当前配置后再决定是否重新保存。";
            default -> "Otra sesión ha guardado la configuración del resumen al mismo tiempo. Tus cambios no se han guardado; revisa la configuración actual antes de volver a guardar.";
        };

        mvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                        .header("Accept-Language", language)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.locale").value(language))
                .andExpect(jsonPath("$.detail").value(expected));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}
