package com.tpverp.saas.tenant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tpverp.saas.access.*;
import com.tpverp.saas.admin.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

class TenantAuthPathTest {
    @Test
    void encodedRequestCannotUseCompanyReadPermissionForMatchedMasterRoute() {
        var users = mock(SaasTenantUserRepository.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var access = mock(TenantAccessService.class);
        var user = new SaasTenantUser(UUID.randomUUID(), null, "path-test", "unused", "VIEWER", true, Instant.now());
        when(users.findByUsernameIgnoreCase("path-test")).thenReturn(Optional.of(user));
        when(sessions.username("session", "tenant")).thenReturn(Optional.of("path-test"));
        when(access.access(user)).thenReturn(new TenantAccessResponse("path-test", List.of(
                new TenantAccessResponse.CompanyAccess(UUID.randomUUID(), "Company", "VIEWER",
                        Set.of(TenantCompanyPrivilege.READ_COMPANY), List.of()))));
        var interceptor = new TenantAuthInterceptor(users, mock(AdminPasswordHasher.class),
                mock(LoginAttemptLimiter.class), sessions, access, false);
        var request = new MockHttpServletRequest("GET", "/api/v1/tenant/%65rp/customers");
        request.addHeader("Authorization", "Bearer session");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/tenant/erp/customers");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
