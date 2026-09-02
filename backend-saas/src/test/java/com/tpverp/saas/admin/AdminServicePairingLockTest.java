package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.SaasPairingCode;
import com.tpverp.saas.license.SaasPairingCodeRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.plan.PlanLimitService;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminServicePairingLockTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void regenerarCodigoBloqueaLaLicenciaAntesDeExpirarYCrear() {
        var companies = mock(SaasCompanyRepository.class);
        var stores = mock(SaasStoreRepository.class);
        var licenses = mock(SaasLicenseRepository.class);
        var installations = mock(SaasInstallationRepository.class);
        var pairingCodes = mock(SaasPairingCodeRepository.class);
        var adminUsers = mock(SaasAdminUserRepository.class);
        var tenantUsers = mock(SaasTenantUserRepository.class);
        var passwordHasher = mock(AdminPasswordHasher.class);
        var integrationSecrets = mock(IntegrationSecretCipher.class);
        var audit = mock(AdminAuditService.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new AdminService(
                companies, stores, licenses, installations, pairingCodes,
                adminUsers, tenantUsers, passwordHasher, integrationSecrets,
                audit, sessions, mock(PlanLimitService.class), jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

        var company = new SaasCompany(
                UUID.randomUUID(), "Empresa", "B12345674",
                TaxpayerType.SOCIEDAD, TaxRegime.IGIC,
                CommercialProfile.MAYORISTA, NOW);
        var store = new SaasStore(
                UUID.randomUUID(), company, "001", "Tienda",
                "Atlantic/Canary", NOW);
        var license = new SaasLicense(
                UUID.randomUUID(), company, "LIC-LOCK-1",
                NOW.plusSeconds(86_400), 1, 0, NOW);
        when(licenses.findByReferenceForUpdate("LIC-LOCK-1"))
                .thenReturn(Optional.of(license));
        when(pairingCodes.findByLicense_ReferenceAndConsumedAtIsNull("LIC-LOCK-1"))
                .thenReturn(List.of());
        when(stores.findByCompany_IdOrderByCodeAsc(company.getId()))
                .thenReturn(List.of(store));
        when(pairingCodes.save(any(SaasPairingCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PairingCodeResponse result = service.regeneratePairingCode("LIC-LOCK-1");

        assertThat(result.licenseReference()).isEqualTo("LIC-LOCK-1");
        verify(licenses).findByReferenceForUpdate("LIC-LOCK-1");
        verify(licenses, never()).findByReference("LIC-LOCK-1");
        verify(pairingCodes).save(any(SaasPairingCode.class));
    }

    @Test
    void repositorioDeclaraBloqueoPesimistaDeEscritura() throws Exception {
        Lock lock = SaasLicenseRepository.class
                .getMethod("findByReferenceForUpdate", String.class)
                .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void traduceColisionConcurrenteDeUsuarioAConflict() {
        var companies = mock(SaasCompanyRepository.class);
        var stores = mock(SaasStoreRepository.class);
        var licenses = mock(SaasLicenseRepository.class);
        var installations = mock(SaasInstallationRepository.class);
        var pairingCodes = mock(SaasPairingCodeRepository.class);
        var adminUsers = mock(SaasAdminUserRepository.class);
        var tenantUsers = mock(SaasTenantUserRepository.class);
        var passwordHasher = mock(AdminPasswordHasher.class);
        var integrationSecrets = mock(IntegrationSecretCipher.class);
        var audit = mock(AdminAuditService.class);
        var sessions = mock(SaasSessionTokenStore.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new AdminService(
                companies, stores, licenses, installations, pairingCodes,
                adminUsers, tenantUsers, passwordHasher, integrationSecrets,
                audit, sessions, mock(PlanLimitService.class), jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        when(adminUsers.existsByUsernameIgnoreCase("simultaneo")).thenReturn(false);
        when(tenantUsers.existsByUsernameIgnoreCase("simultaneo")).thenReturn(false);
        when(passwordHasher.hash("password-segura")).thenReturn("hash");
        when(adminUsers.saveAndFlush(any(SaasAdminUser.class)))
                .thenThrow(new DataIntegrityViolationException("uk_saas_global_username"));

        assertThatThrownBy(() -> service.createUser(new CreateAdminUserRequest(
                        "simultaneo", "password-segura", "VIEWER")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
