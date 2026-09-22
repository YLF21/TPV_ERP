package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SaasAuthenticationControllerTest {
    @Test
    void adminEndpointDoesNotLookUpTenantAccountsOrFallBackAfterWrongAdminPassword() {
        var admins = mock(SaasAdminUserRepository.class);
        var tenants = mock(SaasTenantUserRepository.class);
        var passwords = mock(AdminPasswordHasher.class);
        var clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);
        var state = new InMemorySecurityStateStore();
        var admin = new SaasAdminUser(UUID.randomUUID(), "internal", "admin-hash", true, clock.instant());
        when(admins.findByUsernameIgnoreCase("internal")).thenReturn(Optional.of(admin));
        when(passwords.matches("customer-password", "admin-hash")).thenReturn(false);
        var controller = new SaasAuthenticationController(admins, tenants, passwords,
                new LoginAttemptLimiter(clock, state), new SaasSessionTokenStore(clock, state, java.time.Duration.ofHours(8)));

        assertThatThrownBy(() -> controller.adminLogin(new SaasLoginRequest("internal", "customer-password")))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.adminLogin(new SaasLoginRequest("customer-only", "customer-password")))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(tenants);
    }

    @Test
    void adminEndpointKeepsHashUpgradeAndPasswordChangeStateAndIssuesOnlyAdminSessions() {
        var admins = mock(SaasAdminUserRepository.class);
        var tenants = mock(SaasTenantUserRepository.class);
        var passwords = mock(AdminPasswordHasher.class);
        var clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);
        var state = new InMemorySecurityStateStore();
        var sessions = new SaasSessionTokenStore(clock, state, java.time.Duration.ofHours(8));
        var admin = new SaasAdminUser(UUID.randomUUID(), "internal", "old-hash", true, clock.instant());
        admin.requirePasswordChange();
        when(admins.findByUsernameIgnoreCase("internal")).thenReturn(Optional.of(admin));
        when(passwords.matches("valid-password", "old-hash")).thenReturn(true);
        when(passwords.needsUpgrade("old-hash")).thenReturn(true);
        when(passwords.hash("valid-password")).thenReturn("new-hash");
        var controller = new SaasAuthenticationController(admins, tenants, passwords, new LoginAttemptLimiter(clock, state), sessions);

        var response = controller.adminLogin(new SaasLoginRequest(" internal ", "valid-password"));
        assertThat(response.mode()).isEqualTo("admin");
        assertThat(response.passwordChangeRequired()).isTrue();
        assertThat(admin.getPasswordHash()).isEqualTo("new-hash");
        assertThat(sessions.username(response.accessToken(), "admin")).contains("internal");
        assertThat(sessions.username(response.accessToken(), "tenant")).isEmpty();
        verify(admins).save(admin);
        verifyNoInteractions(tenants);
    }

    @Test
    void rejectsKnownCredentialOutsideLocalAndProductionProfilesBeforeQueryingUsers() {
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
                new LocalAdminCredentialPolicy(Set.of("staging")));

        assertThatThrownBy(() -> controller.login(new SaasLoginRequest("ADMIN", "000")))
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
