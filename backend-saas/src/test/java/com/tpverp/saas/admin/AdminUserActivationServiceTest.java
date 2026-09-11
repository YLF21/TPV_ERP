package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.SaasPairingCodeRepository;
import com.tpverp.saas.license.SaasStoreRepository;
import com.tpverp.saas.plan.PlanLimitService;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminUserActivationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void activationReplacesPasswordClearsForcedChangeRevokesSessionsAndAudits() {
        var adminUsers = mock(SaasAdminUserRepository.class);
        var passwordHasher = mock(AdminPasswordHasher.class);
        var audit = mock(AdminAuditService.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var service = service(adminUsers, passwordHasher, audit, sessions);
        var user = new SaasAdminUser(
                UUID.randomUUID(), "inactive-admin", "old-hash", false, NOW);
        user.requirePasswordChange();
        when(adminUsers.findByUsernameIgnoreCase("INACTIVE-admin"))
                .thenReturn(Optional.of(user));
        when(passwordHasher.hash("new-secure-password"))
                .thenReturn("new-hash");

        service.activateUser(
                "INACTIVE-admin",
                new ChangeAdminPasswordRequest("new-secure-password"));

        assertThat(user.isActive()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.isMustChangePassword()).isFalse();
        verify(sessions).revokeByUser("admin", "inactive-admin");
        verify(audit).log("ACTIVATE_ADMIN_USER", "ADMIN_USER", "inactive-admin");
    }

    @Test
    void activationOfUnknownUserReturnsNotFoundWithoutSecuritySideEffects() {
        var adminUsers = mock(SaasAdminUserRepository.class);
        var passwordHasher = mock(AdminPasswordHasher.class);
        var audit = mock(AdminAuditService.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var service = service(adminUsers, passwordHasher, audit, sessions);
        when(adminUsers.findByUsernameIgnoreCase("missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activateUser(
                        "missing",
                        new ChangeAdminPasswordRequest("new-secure-password")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(passwordHasher, never()).hash("new-secure-password");
        verify(sessions, never()).revokeByUser("admin", "missing");
        verify(audit, never()).log("ACTIVATE_ADMIN_USER", "ADMIN_USER", "missing");
    }

    private static AdminService service(
            SaasAdminUserRepository adminUsers,
            AdminPasswordHasher passwordHasher,
            AdminAuditService audit,
            SaasSessionTokenStore sessions) {
        return new AdminService(
                mock(SaasCompanyRepository.class),
                mock(SaasStoreRepository.class),
                mock(SaasLicenseRepository.class),
                mock(SaasInstallationRepository.class),
                mock(SaasPairingCodeRepository.class),
                adminUsers,
                mock(SaasTenantUserRepository.class),
                passwordHasher,
                mock(IntegrationSecretCipher.class),
                audit,
                sessions,
                mock(PlanLimitService.class),
                mock(JdbcTemplate.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
