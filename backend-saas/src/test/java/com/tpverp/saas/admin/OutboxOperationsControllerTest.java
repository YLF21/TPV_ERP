package com.tpverp.saas.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OutboxOperationsControllerTest {

    @Test
    void requiresAuthenticationForReadRequeueAndAcknowledge() throws Exception {
        Fixture fixture = fixture(Set.of());
        fixture.mvc().perform(get("/api/v1/admin/outbox/failures")).andExpect(status().isUnauthorized());
        fixture.mvc().perform(post("/api/v1/admin/outbox/security/{id}/requeue", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Incidencia resuelta\"}"))
                .andExpect(status().isUnauthorized());
        fixture.mvc().perform(post("/api/v1/admin/outbox/integrations/{id}/acknowledge", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Revision manual\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requiresManageOperationsForReadAndMutations() throws Exception {
        Fixture fixture = fixture(Set.of(AdminPermission.VIEW_ADMIN_DATA.name()));
        fixture.mvc().perform(get("/api/v1/admin/outbox/failures").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        fixture.mvc().perform(post("/api/v1/admin/outbox/security/{id}/requeue", UUID.randomUUID())
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Incidencia resuelta\"}"))
                .andExpect(status().isForbidden());
        fixture.mvc().perform(post("/api/v1/admin/outbox/integrations/{id}/acknowledge", UUID.randomUUID())
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Revision manual\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizedOperatorCanPageRequeueAndAcknowledge() throws Exception {
        Fixture fixture = fixture(Set.of(AdminPermission.MANAGE_OPERATIONS.name()));
        UUID failureId = UUID.randomUUID();
        when(fixture.operations().failures("SECURITY", 25, null)).thenReturn(
                new OutboxFailurePageResponse(List.of(new OutboxFailureResponse(
                        failureId, "SECURITY", "PASSWORD_RESET_REQUESTED", 8,
                        "CHANNEL_UNAVAILABLE", Instant.parse("2026-09-08T18:00:00Z"))), "next"));

        fixture.mvc().perform(get("/api/v1/admin/outbox/failures")
                        .header("Authorization", "Bearer token")
                        .param("channel", "SECURITY").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(failureId.toString()))
                .andExpect(jsonPath("$.nextCursor").value("next"));
        fixture.mvc().perform(post("/api/v1/admin/outbox/security/{id}/requeue", failureId)
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Incidencia resuelta\"}"))
                .andExpect(status().isNoContent());
        fixture.mvc().perform(post("/api/v1/admin/outbox/integrations/{id}/acknowledge", failureId)
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Revision manual completada\"}"))
                .andExpect(status().isNoContent());

        verify(fixture.operations()).requeueSecurity(failureId, "Incidencia resuelta");
        verify(fixture.operations()).acknowledgeIntegration(failureId, "Revision manual completada");
    }

    private Fixture fixture(Set<String> permissions) {
        OutboxOperationsService operations = mock(OutboxOperationsService.class);
        SaasAdminUserRepository users = mock(SaasAdminUserRepository.class);
        SaasSessionTokenStore sessions = mock(SaasSessionTokenStore.class);
        LoginAttemptLimiter attempts = mock(LoginAttemptLimiter.class);
        SaasAdminUser operator = new SaasAdminUser(UUID.randomUUID(), "operator", "unused", true, Instant.now());
        when(sessions.username(eq("token"), eq("admin"))).thenReturn(Optional.of("operator"));
        when(users.findByUsernameIgnoreCase("operator")).thenReturn(Optional.of(operator));
        when(users.permissionCodes("operator")).thenReturn(permissions);
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(users, new AdminPasswordHasher(),
                attempts, sessions, new LocalAdminCredentialPolicy(Set.of("test")), false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OutboxOperationsController(operations))
                .addInterceptors(interceptor).build();
        return new Fixture(mvc, operations);
    }

    private record Fixture(MockMvc mvc, OutboxOperationsService operations) {
    }
}
