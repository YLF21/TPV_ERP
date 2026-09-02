package com.tpverp.saas.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PasswordLifecycleServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);
    private SaasAdminUserRepository admins;
    private SaasTenantUserRepository tenants;
    private JdbcTemplate jdbc;
    private AdminPasswordHasher passwords;
    private SaasSessionTokenStore sessions;
    private PasswordLifecycleService service;

    @BeforeEach
    void setUp() {
        admins = mock(SaasAdminUserRepository.class);
        tenants = mock(SaasTenantUserRepository.class);
        jdbc = mock(JdbcTemplate.class);
        passwords = new AdminPasswordHasher();
        sessions = new SaasSessionTokenStore(clock, new InMemorySecurityStateStore(), Duration.ofHours(8));
        service = new PasswordLifecycleService(admins, tenants, passwords, sessions, jdbc,
                new IntegrationSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), clock, Duration.ofMinutes(30));
    }

    @Test
    void authenticatedAdminChangeClearsRequirementAndRevokesEverySession() {
        SaasAdminUser user = new SaasAdminUser(UUID.randomUUID(), "ADMIN",
                passwords.hash("old-password"), true, clock.instant());
        user.requirePasswordChange();
        when(admins.findByUsernameIgnoreCase("ADMIN")).thenReturn(Optional.of(user));
        var first = sessions.issue("admin", "ADMIN");
        var second = sessions.issue("admin", "ADMIN");

        service.changeAuthenticated(first.token(), "old-password", "new-password-123");

        assertFalse(user.isMustChangePassword());
        assertTrue(passwords.matches("new-password-123", user.getPasswordHash()));
        assertTrue(sessions.session(first.token()).isEmpty());
        assertTrue(sessions.session(second.token()).isEmpty());
        verify(admins).save(user);
        verify(tenants, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tenantChangeCannotMutateAdminRealm() {
        SaasTenantUser tenant = new SaasTenantUser(UUID.randomUUID(), null, "owner@example.test",
                passwords.hash("old-password"), "OWNER", true, clock.instant());
        when(tenants.findByUsernameIgnoreCase("owner@example.test")).thenReturn(Optional.of(tenant));
        var token = sessions.issue("tenant", "owner@example.test");

        service.changeAuthenticated(token.token(), "old-password", "new-password-123");

        assertTrue(passwords.matches("new-password-123", tenant.getPasswordHash()));
        verify(tenants).save(tenant);
        verifyNoInteractions(admins);
    }

    @Test
    void wrongCurrentPasswordDoesNotRevokeSessions() {
        SaasAdminUser user = new SaasAdminUser(UUID.randomUUID(), "secure-admin",
                passwords.hash("old-password"), true, clock.instant());
        when(admins.findByUsernameIgnoreCase("secure-admin")).thenReturn(Optional.of(user));
        var token = sessions.issue("admin", "secure-admin");

        assertThrows(ResponseStatusException.class,
                () -> service.changeAuthenticated(token.token(), "wrong-password", "new-password-123"));

        assertTrue(sessions.session(token.token()).isPresent());
        verify(admins, never()).save(user);
    }

    @Test
    void recoveryRequestDoesNotRevealOrPersistAnythingForUnknownAccount() {
        when(admins.findByUsernameIgnoreCase("missing")).thenReturn(Optional.empty());
        when(tenants.findByUsernameIgnoreCase("missing")).thenReturn(Optional.empty());

        service.requestReset(" Missing ", "127.0.0.1");

        verifyNoInteractions(jdbc);
    }
}
