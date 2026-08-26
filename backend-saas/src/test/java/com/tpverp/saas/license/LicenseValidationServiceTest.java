package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LicenseValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    void devuelveCaducadaAunqueElEstadoPersistidoSigaSiendoValida() {
        var company = new SaasCompany(
                UUID.randomUUID(), "Empresa", "B12345674",
                TaxpayerType.SOCIEDAD, TaxRegime.IVA, NOW.minusSeconds(3600));
        var store = new SaasStore(
                UUID.randomUUID(), company, "001", "Tienda",
                "Europe/Madrid", NOW.minusSeconds(3600));
        var license = new SaasLicense(
                UUID.randomUUID(), company, "LIC-1", NOW.minusSeconds(1), 3, 2,
                NOW.minusSeconds(3600));
        UUID installationId = UUID.randomUUID();
        var installation = new SaasInstallation(
                UUID.randomUUID(), company, store, license, installationId, "INST-1",
                "public-key", "token-hash", NOW.minusSeconds(3600));
        var installations = mock(SaasInstallationRepository.class);
        var authenticator = mock(InstallationAuthenticator.class);
        var policies = mock(VerifactuActivationPolicyResolver.class);
        when(installations.findByInstallationIdAndLicense_Reference(installationId, "LIC-1"))
                .thenReturn(Optional.of(installation));
        when(policies.required(TaxpayerType.SOCIEDAD)).thenReturn(new VerifactuPolicySnapshot(
                LocalDate.of(2027, 1, 1), 4, NOW.minusSeconds(60)));
        var service = new LicenseValidationService(
                installations, authenticator, Clock.fixed(NOW, ZoneOffset.UTC), policies);

        LicenseSaasValidationResponse response = service.validate(
                new LicenseSaasValidationRequest(
                        installationId, "INST-1", store.getId(), "LIC-1", "hash"),
                "token");

        assertThat(response.status()).isEqualTo(LicenseSaasStatus.CADUCADA);
        assertThat(response.maxWindows()).isEqualTo(3);
        assertThat(response.maxPda()).isEqualTo(2);
        assertThat(response.licenseVersion()).isEqualTo(1);
        assertThat(installation.getLastValidatedAt()).isEqualTo(NOW);
        verify(authenticator).requireToken(installation, "token");
    }

    @Test
    void unaInstalacionRevocadaRecibeBloqueoAutoritativoSinActualizarSuUltimaValidacion() {
        var company = new SaasCompany(
                UUID.randomUUID(), "Empresa", "B12345674",
                TaxpayerType.SOCIEDAD, TaxRegime.IVA, NOW.minusSeconds(3600));
        var store = new SaasStore(
                UUID.randomUUID(), company, "001", "Tienda",
                "Europe/Madrid", NOW.minusSeconds(3600));
        var license = new SaasLicense(
                UUID.randomUUID(), company, "LIC-REV", NOW.plusSeconds(86400), 3, 2,
                NOW.minusSeconds(3600));
        UUID installationId = UUID.randomUUID();
        var installation = new SaasInstallation(
                UUID.randomUUID(), company, store, license, installationId, "INST-REV",
                "public-key", "token-hash", NOW.minusSeconds(3600));
        installation.revoke(NOW.minusSeconds(30), "admin", "equipo retirado");
        var installations = mock(SaasInstallationRepository.class);
        var authenticator = mock(InstallationAuthenticator.class);
        var policies = mock(VerifactuActivationPolicyResolver.class);
        when(installations.findByInstallationIdAndLicense_Reference(installationId, "LIC-REV"))
                .thenReturn(Optional.of(installation));
        when(policies.required(TaxpayerType.SOCIEDAD)).thenReturn(new VerifactuPolicySnapshot(
                LocalDate.of(2027, 1, 1), 4, NOW.minusSeconds(60)));
        var service = new LicenseValidationService(
                installations, authenticator, Clock.fixed(NOW, ZoneOffset.UTC), policies);

        LicenseSaasValidationResponse response = service.validate(
                new LicenseSaasValidationRequest(
                        installationId, "INST-REV", store.getId(), "LIC-REV", "hash"),
                "token");

        assertThat(response.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(installation.getLastValidatedAt()).isNull();
        verify(authenticator).requireKnownToken(installation, "token");
        verify(authenticator, never()).requireToken(installation, "token");
    }
}
