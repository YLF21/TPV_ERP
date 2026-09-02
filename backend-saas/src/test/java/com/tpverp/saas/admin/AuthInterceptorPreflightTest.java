package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tpverp.saas.tenant.SaasTenantUserRepository;
import com.tpverp.saas.tenant.TenantAuthInterceptor;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthInterceptorPreflightTest {

    @Test
    void adminInterceptorLeavesCorsPreflightToCorsConfiguration() throws Exception {
        SaasAdminUserRepository users = mock(SaasAdminUserRepository.class);
        LoginAttemptLimiter attempts = mock(LoginAttemptLimiter.class);
        SaasSessionTokenStore sessions = mock(SaasSessionTokenStore.class);
        var interceptor = new AdminAuthInterceptor(users, new AdminPasswordHasher(), attempts, sessions,
                new LocalAdminCredentialPolicy(Set.of("test")), false);
        var request = preflight("/api/v2/admin/test");
        var response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(users, attempts, sessions);
    }

    @Test
    void tenantInterceptorLeavesCorsPreflightToCorsConfiguration() throws Exception {
        SaasTenantUserRepository users = mock(SaasTenantUserRepository.class);
        LoginAttemptLimiter attempts = mock(LoginAttemptLimiter.class);
        SaasSessionTokenStore sessions = mock(SaasSessionTokenStore.class);
        var interceptor = new TenantAuthInterceptor(users, new AdminPasswordHasher(), attempts, sessions, false);
        var request = preflight("/api/v1/tenant/me");
        var response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(users, attempts, sessions);
    }

    private static MockHttpServletRequest preflight(String path) {
        var request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", "http://127.0.0.1:5175");
        request.addHeader("Access-Control-Request-Method", "GET");
        return request;
    }
}
