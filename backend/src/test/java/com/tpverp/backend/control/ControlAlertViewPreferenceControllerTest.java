package com.tpverp.backend.control;

import static org.mockito.ArgumentMatchers.any;
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

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

@WebMvcTest(ControlAlertViewPreferenceController.class)
@Import(ControlAlertViewPreferenceControllerTest.MethodSecurityConfiguration.class)
class ControlAlertViewPreferenceControllerTest {

    private static final String PATH = "/api/v1/control/alerts/view-preference";
    private static final ControlAlertViewPreferenceService.Settings SETTINGS =
            ControlAlertViewPreferenceService.Settings.defaults();
    private static final String BODY = new ObjectMapper().writeValueAsString(SETTINGS);
    private static final ControlAlertViewPreferenceService.View VIEW =
            new ControlAlertViewPreferenceService.View(true, true, true, false,
                    "LAST_7_DAYS", 30, "occurredAt", "desc", "Atlantic/Canary", "es-ES");

    @Autowired private MockMvc mvc;
    @MockitoBean private ControlAlertViewPreferenceService service;

    @ParameterizedTest
    @MethodSource("allowedAuthorities")
    void readersManagersAndAdministratorsCanReadAndSaveTheirOwnView(String[] permissions) throws Exception {
        var requestUser = user("reader").authorities(authorities(permissions));
        when(service.get(any(Authentication.class))).thenReturn(VIEW);
        when(service.save(eq(SETTINGS), any(Authentication.class))).thenReturn(VIEW);

        mvc.perform(get(PATH).with(requestUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeTimezone").value("Atlantic/Canary"))
                .andExpect(jsonPath("$.storeLocale").value("es-ES"));
        mvc.perform(put(PATH).with(requestUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPeriod").value("LAST_7_DAYS"));

        verify(service).get(org.mockito.ArgumentMatchers.argThat(auth -> auth.getName().equals("reader")));
        verify(service).save(eq(SETTINGS),
                org.mockito.ArgumentMatchers.argThat(auth -> auth.getName().equals("reader")));
    }

    @ParameterizedTest
    @MethodSource("deniedAuthorities")
    void requiresAppAccessAndAnAlertPermissionBeforeCallingTheService(String[] permissions) throws Exception {
        var requestUser = user("other").authorities(authorities(permissions));

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
    void rejectsInvalidOrMissingSettingsDuringRequestBinding() throws Exception {
        for (var body : new String[]{"null", "{}", BODY.replace("LAST_7_DAYS", "ALL_TIME"),
                BODY.replace("\"showDetail\":true", "\"showDetail\":null")}) {
            mvc.perform(put(PATH).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    private static SimpleGrantedAuthority[] authorities(String[] permissions) {
        return Arrays.stream(permissions).map(SimpleGrantedAuthority::new)
                .toArray(SimpleGrantedAuthority[]::new);
    }

    static Stream<Arguments> allowedAuthorities() {
        return Stream.of(
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE"}),
                Arguments.of((Object) new String[]{"ROLE_ADMIN"}));
    }

    static Stream<Arguments> deniedAuthorities() {
        return Stream.of(
                Arguments.of((Object) new String[]{"ROLE_USER"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS"}),
                Arguments.of((Object) new String[]{"CONTROL_ALERTS_READ"}),
                Arguments.of((Object) new String[]{"CONTROL_ALERTS_MANAGE"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "CONTROL_RULES_MANAGE"}));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}
