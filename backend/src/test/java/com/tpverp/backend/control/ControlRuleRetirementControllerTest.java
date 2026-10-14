package com.tpverp.backend.control;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ControlRuleController.class)
@Import(ControlRuleRetirementControllerTest.MethodSecurityConfiguration.class)
class ControlRuleRetirementControllerTest {
    private static final UUID ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/control/rules/" + ID;
    @Autowired private MockMvc mvc;
    @MockitoBean private ControlRuleService service;

    @Test
    void administratorsCanRetireWithExpectedVersion() throws Exception {
        mvc.perform(delete(PATH).param("version", "4").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).retire(eq(ID), eq(4L), any(Authentication.class));
    }

    @Test
    void ruleManagersWithAppAccessCanRetire() throws Exception {
        mvc.perform(delete(PATH).param("version", "4").with(user("manager").authorities(
                        new SimpleGrantedAuthority("APP_GESTION_ACCESS"),
                        new SimpleGrantedAuthority("CONTROL_RULES_MANAGE"))).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).retire(eq(ID), eq(4L), any(Authentication.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_GESTION_ACCESS", "CONTROL_RULES_MANAGE", "CONTROL_ALERTS_MANAGE"})
    void partialPermissionsCannotRetire(String permission) throws Exception {
        mvc.perform(delete(PATH).param("version", "0").with(user("user").authorities(
                        new SimpleGrantedAuthority(permission))).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void alertManagersCannotRetireRules() throws Exception {
        mvc.perform(delete(PATH).param("version", "0").with(user("user").authorities(
                        new SimpleGrantedAuthority("APP_GESTION_ACCESS"),
                        new SimpleGrantedAuthority("CONTROL_ALERTS_MANAGE"))).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void requiresAuthenticationAndNumericVersion() throws Exception {
        mvc.perform(delete(PATH).param("version", "0").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(PATH).param("version", "invalid").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void mapsConcurrencyConflictsAndMissingRules() throws Exception {
        doThrow(new IllegalStateException("Conflicto de version"))
                .when(service).retire(eq(ID), eq(0L), any(Authentication.class));
        mvc.perform(delete(PATH).param("version", "0").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isConflict());
        doThrow(new NoSuchElementException("Regla de control no encontrada"))
                .when(service).retire(eq(ID), eq(1L), any(Authentication.class));
        mvc.perform(delete(PATH).param("version", "1").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}
