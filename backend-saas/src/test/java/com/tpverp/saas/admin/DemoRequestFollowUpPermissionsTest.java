package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DemoRequestFollowUpPermissionsTest {
    private final SaasAdminUserRepository users = mock(SaasAdminUserRepository.class);
    private final SaasSessionTokenStore sessions = mock(SaasSessionTokenStore.class);
    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(users, new AdminPasswordHasher(),
            mock(LoginAttemptLimiter.class), sessions, new LocalAdminCredentialPolicy(Set.of("test")), false);
    private final String path = "/api/v1/admin/demo-requests/" + UUID.randomUUID() + "/follow-up";

    @Test
    void anonymousAndTenantTokensCannotReadInternalNotesOrAssignContacts() throws Exception {
        for (String route : Set.of(path, "/api/v1/admin/demo-requests/assignees")) {
            var response = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", route), response, new Object())).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
            var tenant = new MockHttpServletRequest("GET", route);
            tenant.addHeader("Authorization", "Bearer tenant-only-token");
            when(sessions.username("tenant-only-token", "admin")).thenReturn(Optional.empty());
            var tenantResponse = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(tenant, tenantResponse, new Object())).isFalse();
            assertThat(tenantResponse.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void readingNeedsViewPermissionAndUpdatingNeedsViewPlusOperations() throws Exception {
        login(Set.of(AdminPermission.VIEW_ADMIN_DATA.name()));
        assertThat(interceptor.preHandle(request("GET"), new MockHttpServletResponse(), new Object())).isTrue();
        var viewerUpdate = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("PUT"), viewerUpdate, new Object())).isFalse();
        assertThat(viewerUpdate.getStatus()).isEqualTo(403);

        login(Set.of(AdminPermission.MANAGE_OPERATIONS.name()));
        var operationsOnly = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("PUT"), operationsOnly, new Object())).isFalse();
        assertThat(operationsOnly.getStatus()).isEqualTo(403);

        login(Set.of(AdminPermission.VIEW_ADMIN_DATA.name(), AdminPermission.MANAGE_OPERATIONS.name()));
        var update = request("PUT");
        assertThat(interceptor.preHandle(update, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(update.getAttribute(AdminAuditService.USERNAME_ATTRIBUTE)).isEqualTo("operator");
    }

    @Test
    void disabledOperatorsCannotAccessTheFollowUpEvenWithBothPermissions() throws Exception {
        login(Set.of(AdminPermission.VIEW_ADMIN_DATA.name(), AdminPermission.MANAGE_OPERATIONS.name()));
        var user = new SaasAdminUser(UUID.randomUUID(), "operator", "unused", false, Instant.now());
        when(users.findByUsernameIgnoreCase("operator")).thenReturn(Optional.of(user));
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("PUT"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private void login(Set<String> permissions) {
        when(sessions.username("operator-token", "admin")).thenReturn(Optional.of("operator"));
        when(users.findByUsernameIgnoreCase("operator")).thenReturn(Optional.of(new SaasAdminUser(
                UUID.randomUUID(), "operator", "unused", true, Instant.now())));
        when(users.permissionCodes("operator")).thenReturn(permissions);
    }

    private MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer operator-token");
        return request;
    }
}
