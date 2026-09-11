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

class AdminUserActivationPermissionTest {

    @Test
    void activationRequiresManageAdminUsersPermission() throws Exception {
        var users = mock(SaasAdminUserRepository.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var interceptor = new AdminAuthInterceptor(
                users,
                new AdminPasswordHasher(),
                mock(LoginAttemptLimiter.class),
                sessions,
                new LocalAdminCredentialPolicy(Set.of("test")),
                false);
        var operator = new SaasAdminUser(
                UUID.randomUUID(), "viewer", "unused", true, Instant.now());
        when(sessions.username("viewer-token", "admin"))
                .thenReturn(Optional.of("viewer"));
        when(users.findByUsernameIgnoreCase("viewer"))
                .thenReturn(Optional.of(operator));
        when(users.permissionCodes("viewer"))
                .thenReturn(Set.of(AdminPermission.VIEW_ADMIN_DATA.name()));
        var request = activationRequest("viewer-token");
        var response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void activationAllowsManageAdminUsersPermission() throws Exception {
        var users = mock(SaasAdminUserRepository.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var interceptor = new AdminAuthInterceptor(
                users,
                new AdminPasswordHasher(),
                mock(LoginAttemptLimiter.class),
                sessions,
                new LocalAdminCredentialPolicy(Set.of("test")),
                false);
        var operator = new SaasAdminUser(
                UUID.randomUUID(), "administrator", "unused", true, Instant.now());
        when(sessions.username("admin-token", "admin"))
                .thenReturn(Optional.of("administrator"));
        when(users.findByUsernameIgnoreCase("administrator"))
                .thenReturn(Optional.of(operator));
        when(users.permissionCodes("administrator"))
                .thenReturn(Set.of(AdminPermission.MANAGE_ADMIN_USERS.name()));
        var request = activationRequest("admin-token");
        var response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(AdminAuditService.USERNAME_ATTRIBUTE))
                .isEqualTo("administrator");
    }

    private static MockHttpServletRequest activationRequest(String token) {
        var request = new MockHttpServletRequest(
                "PUT", "/api/v1/admin/users/inactive/activation");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
