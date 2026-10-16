package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DemoRequestPermissionsTest {
    @Test
    void anonymousCannotReadOrMutateAndViewerCannotUpdateStatus() throws Exception {
        var users = mock(SaasAdminUserRepository.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var interceptor = interceptor(users, sessions);
        for (String method : Set.of("GET", "PUT")) {
            var response = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(new MockHttpServletRequest(method,
                    "/api/v1/admin/demo-requests/" + UUID.randomUUID() + "/status"), response, new Object())).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }
        when(sessions.username("viewer-token", "admin")).thenReturn(Optional.of("viewer"));
        when(users.findByUsernameIgnoreCase("viewer")).thenReturn(Optional.of(new SaasAdminUser(
                UUID.randomUUID(), "viewer", "unused", true, Instant.now())));
        when(users.permissionCodes("viewer")).thenReturn(Set.of(AdminPermission.VIEW_ADMIN_DATA.name()));
        var update = new MockHttpServletRequest("PUT", "/api/v1/admin/demo-requests/" + UUID.randomUUID() + "/status");
        update.addHeader("Authorization", "Bearer viewer-token");
        var denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(update, denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
        var read = new MockHttpServletRequest("GET", "/api/v1/admin/demo-requests/page");
        read.addHeader("Authorization", "Bearer viewer-token");
        assertThat(interceptor.preHandle(read, new MockHttpServletResponse(), new Object())).isTrue();
        when(users.permissionCodes("viewer")).thenReturn(Set.of(AdminPermission.MANAGE_OPERATIONS.name()));
        assertThat(interceptor.preHandle(update, new MockHttpServletResponse(), new Object())).isTrue();
    }

    private static AdminAuthInterceptor interceptor(SaasAdminUserRepository users, SaasSessionTokenStore sessions) {
        return new AdminAuthInterceptor(users, new AdminPasswordHasher(), mock(LoginAttemptLimiter.class),
                sessions, new LocalAdminCredentialPolicy(Set.of("test")), false);
    }
}
