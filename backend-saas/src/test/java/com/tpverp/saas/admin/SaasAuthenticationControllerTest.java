package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SaasAuthenticationControllerTest {
    @Test
    void rejectsLocalCredentialOutsideLocalProfileBeforeQueryingUsers() {
        SaasAdminUserRepository admins = mock(SaasAdminUserRepository.class);
        SaasTenantUserRepository tenants = mock(SaasTenantUserRepository.class);
        AdminPasswordHasher passwords = mock(AdminPasswordHasher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);
        var securityState = new InMemorySecurityStateStore();
        var controller = new SaasAuthenticationController(
                admins,
                tenants,
                passwords,
                new LoginAttemptLimiter(clock, securityState),
                new SaasSessionTokenStore(clock, securityState, java.time.Duration.ofHours(8)),
                new LocalAdminCredentialPolicy(Set.of("prod")));

        assertThatThrownBy(() -> controller.login(new SaasLoginRequest("ADMIN", "0000")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(admins, tenants, passwords);
    }

    @Test
    void rejectsAmbiguousUsernameWithoutTryingEitherRealmPassword() {
        SaasAdminUserRepository admins = mock(SaasAdminUserRepository.class);
        SaasTenantUserRepository tenants = mock(SaasTenantUserRepository.class);
        AdminPasswordHasher passwords = mock(AdminPasswordHasher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);
        var securityState = new InMemorySecurityStateStore();
        var attempts = new LoginAttemptLimiter(clock, securityState);
        var sessions = new SaasSessionTokenStore(clock, securityState, java.time.Duration.ofHours(8));
        var admin = mock(SaasAdminUser.class);
        var tenant = mock(SaasTenantUser.class);
        when(admins.findByUsernameIgnoreCase("ambiguous")).thenReturn(Optional.of(admin));
        when(tenants.findByUsernameIgnoreCase("ambiguous")).thenReturn(Optional.of(tenant));
        var controller = new SaasAuthenticationController(
                admins, tenants, passwords, attempts, sessions);

        assertThatThrownBy(() -> controller.login(
                        new SaasLoginRequest("ambiguous", "password-value")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(passwords);
    }
}
