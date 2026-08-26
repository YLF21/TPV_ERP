package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LicenseSaasValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private final InstallationRepository installations = org.mockito.Mockito.mock(InstallationRepository.class);
    private final StoreRepository stores = org.mockito.Mockito.mock(StoreRepository.class);
    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);
    private final LicenseSaasValidationClient client = org.mockito.Mockito.mock(LicenseSaasValidationClient.class);
    private final LicenseSaasCredentialStore credentials =
            org.mockito.Mockito.mock(LicenseSaasCredentialStore.class);
    private final LicenseSaasCacheAuthenticator cacheAuthenticator =
            new LicenseSaasCacheAuthenticator(credentials);
    private final LicenseSaasValidationService service = new LicenseSaasValidationService(
            installations,
            stores,
            licenses,
            client,
            Clock.fixed(NOW, ZoneOffset.UTC),
            cacheAuthenticator);

    @BeforeEach
    void protectedInstallationTokenIsAvailable() {
        when(credentials.readToken()).thenReturn(Optional.of("installation-token"));
    }

    @Test
    void actualizaUltimaValidacionYVigenciaCuandoSaasDevuelveValida() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.of(license));
        when(client.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                license.getReferencia(),
                license.getHash())))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.VALIDA,
                        Instant.parse("2027-08-10T00:00:00Z"),
                        java.time.LocalDate.of(2027, 1, 1),
                        4,
                        Instant.parse("2026-07-22T10:00:00Z"),
                        CommercialProfile.MINORISTA,
                        3,
                        2,
                        7));

        service.validateActiveLicense();

        assertThat(license.getUltimaValidacionSaas()).isEqualTo(NOW);
        assertThat(license.getValidaHasta()).isEqualTo(Instant.parse("2027-08-10T00:00:00Z"));
        assertThat(license.getEstadoSaas()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(license.getVerifactuActivationDate()).isEqualTo(java.time.LocalDate.of(2027, 1, 1));
        assertThat(license.getVerifactuPolicyVersion()).isEqualTo(4L);
        assertThat(license.getCommercialProfile()).isEqualTo(CommercialProfile.MINORISTA);
        assertThat(license.getMaxWindows()).isEqualTo(3);
        assertThat(license.getMaxPda()).isEqualTo(2);
        assertThat(license.getSaasLicenseVersion()).isEqualTo(7);
        assertThat(license.getFormatVersion()).isEqualTo(6);
        assertThat(cacheAuthenticator.isAuthentic(license)).isTrue();
        verify(licenses).save(license);
    }

    @Test
    void noRetrasaUnaPoliticaVerifactuQueYaEntroEnVigor() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        license.applyVerifactuPolicy(
                java.time.LocalDate.of(2026, 8, 1),
                1,
                Instant.parse("2026-07-01T00:00:00Z"));
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.of(license));
        when(client.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.VALIDA,
                        Instant.parse("2027-08-10T00:00:00Z"),
                        java.time.LocalDate.of(2027, 1, 1),
                        2,
                        Instant.parse("2026-08-10T09:00:00Z")));

        service.validateActiveLicense();

        assertThat(license.getVerifactuActivationDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
        assertThat(license.getVerifactuPolicyVersion()).isEqualTo(1L);
        verify(licenses).save(license);
    }

    @Test
    void bloqueaManualCuandoSaasDevuelveBloqueadaManual() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.of(license));
        when(client.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                license.getReferencia(),
                license.getHash())))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.BLOQUEADA_MANUAL,
                        license.getValidaHasta()));

        service.validateActiveLicense();

        assertThat(license.getUltimaValidacionSaas()).isEqualTo(NOW);
        assertThat(license.getEstadoSaas()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(license.isOperationalAt(NOW)).isFalse();
        verify(licenses).save(license);
    }

    @Test
    void caducadaPorSaasBloqueaYConservaEstadoExacto() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.of(license));
        when(client.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                license.getReferencia(),
                license.getHash())))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.CADUCADA,
                        NOW.minusSeconds(1)));

        service.validateActiveLicense();

        assertThat(license.getEstadoSaas()).isEqualTo(LicenseSaasStatus.CADUCADA);
        assertThat(license.isOperationalAt(NOW)).isFalse();
        verify(licenses).save(license);
    }

    @Test
    void noHaceNadaSiNoHayLicenciaActiva() {
        var installation = installation();
        var store = store();
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.empty());

        service.validateActiveLicense();

        verify(client, never()).validate(org.mockito.Mockito.any());
        verify(licenses, never()).save(org.mockito.Mockito.any());
    }

    @Test
    void incluyeLegacyFormatoCuatroSinVersionSaasParaSuPrimerUpgrade() {
        License legacy = license(store(), installation());
        when(licenses.findByActivaTrueOrderByValidaDesdeDesc()).thenReturn(List.of(legacy));

        assertThat(service.activeSaasLicenseIds()).containsExactly(legacy.getId());
    }

    @Test
    void scheduledRefreshLocksTheSelectedLicenseBeforeCallingSaas() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        when(licenses.findByIdForSaasValidationForUpdate(license.getId()))
                .thenReturn(Optional.of(license));
        when(installations.findById(installation.getId()))
                .thenReturn(Optional.of(installation));
        when(stores.findWithCompanyById(store.getId()))
                .thenReturn(Optional.of(store));
        when(client.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.VALIDA,
                        Instant.parse("2027-08-10T00:00:00Z")));

        service.validateLicense(license.getId());

        verify(licenses).findByIdForSaasValidationForUpdate(license.getId());
        verify(licenses).save(license);
    }

    @Test
    void recuperaFormatoCincoConRespuestaSaasAutoritativaTrasRoundTripPostgresql() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        license.applySaasLicenseSnapshot(
                NOW,
                LicenseSaasStatus.VALIDA,
                Instant.parse("2027-08-10T00:00:00Z"),
                2,
                1,
                7);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "formatVersion", 5);
        String legacyMac = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                cacheAuthenticator,
                "mac",
                license,
                "installation-token",
                "TPV-ERP-SAAS-LICENSE-CACHE-V5",
                false);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "hash", legacyMac);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license,
                "validaDesde",
                license.getValidaDesde().plusNanos(1_000));
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.of(license));
        when(client.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.VALIDA,
                        Instant.parse("2027-08-10T00:00:00Z"),
                        null,
                        0,
                        null,
                        CommercialProfile.MAYORISTA,
                        2,
                        1,
                        7,
                        license.getSaasCompanyId(),
                        license.getSaasStoreId(),
                        license.getReferencia(),
                        license.getTaxId()));

        service.validateActiveLicense();

        assertThat(license.getFormatVersion()).isEqualTo(6);
        assertThat(cacheAuthenticator.isAuthentic(license)).isTrue();
        verify(licenses).save(license);
    }

    @Test
    void noActualizaFormatoCincoSiLaRespuestaNoEstaVinculadaAIdentidadCentral() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        license.applySaasLicenseSnapshot(
                NOW,
                LicenseSaasStatus.VALIDA,
                Instant.parse("2027-08-10T00:00:00Z"),
                2,
                1,
                7);
        org.springframework.test.util.ReflectionTestUtils.setField(license, "formatVersion", 5);
        String legacyMac = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                cacheAuthenticator,
                "mac",
                license,
                "installation-token",
                "TPV-ERP-SAAS-LICENSE-CACHE-V5",
                false);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license, "hash", legacyMac);
        org.springframework.test.util.ReflectionTestUtils.setField(
                license,
                "validaDesde",
                license.getValidaDesde().plusNanos(1_000));
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(store.getId(), installation.getId()))
                .thenReturn(Optional.of(license));
        when(client.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LicenseSaasValidationResponse(
                        LicenseSaasStatus.VALIDA,
                        Instant.parse("2027-08-10T00:00:00Z"),
                        null,
                        0,
                        null,
                        CommercialProfile.MAYORISTA,
                        2,
                        1,
                        7,
                        UUID.randomUUID(),
                        license.getSaasStoreId(),
                        license.getReferencia(),
                        license.getTaxId()));

        assertThatThrownBy(service::validateActiveLicense)
                .isInstanceOf(com.tpverp.backend.licensing.application.LicenseValidationException.class)
                .hasMessageContaining("identidad central");
        assertThat(license.getFormatVersion()).isEqualTo(5);
        verify(licenses, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void consultaLaLicenciaDeLaTiendaAutenticadaSinElegirLaPrimeraTienda() {
        var installation = installation();
        var authenticatedStore = store();
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        var scopedService = new LicenseSaasValidationService(
                installations,
                stores,
                licenses,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC),
                organization,
                cacheAuthenticator);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(organization.currentStore()).thenReturn(authenticatedStore);
        when(licenses.findActiveForSaasValidationForUpdate(
                authenticatedStore.getId(), installation.getId()))
                .thenReturn(Optional.empty());

        scopedService.validateActiveLicense();

        verify(stores, never()).findAll();
        verify(licenses).findActiveForSaasValidationForUpdate(
                authenticatedStore.getId(), installation.getId());
    }

    @Test
    void rechazaCondicionesDistintasConLaMismaVersionSaas() {
        var license = license(store(), installation());
        Instant validUntil = Instant.parse("2027-08-10T00:00:00Z");
        license.applySaasLicenseSnapshot(
                NOW, LicenseSaasStatus.VALIDA, validUntil, 2, 1, 5);

        assertThatThrownBy(() -> license.applySaasLicenseSnapshot(
                NOW.plusSeconds(60), LicenseSaasStatus.VALIDA, validUntil, 3, 1, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("misma version");
    }

    @Test
    void rechazaCacheFormatoSeisManipuladoAntesDeConsultarSaas() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        license.applySaasLicenseSnapshot(
                NOW, LicenseSaasStatus.VALIDA,
                Instant.parse("2027-08-10T00:00:00Z"), 2, 1, 1);
        cacheAuthenticator.seal(license);
        license.markSaasBlocked(NOW.plusSeconds(1));
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(
                store.getId(), installation.getId())).thenReturn(Optional.of(license));

        assertThatThrownBy(service::validateActiveLicense)
                .isInstanceOf(com.tpverp.backend.licensing.application.LicenseValidationException.class)
                .hasMessageContaining("no es autentico");

        verify(client, never()).validate(org.mockito.Mockito.any());
        verify(licenses, never()).save(org.mockito.Mockito.any());
    }

    @Test
    void rechazaCacheFormatoSeisSiFaltaElTokenProtegido() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        license.applySaasLicenseSnapshot(
                NOW, LicenseSaasStatus.VALIDA,
                Instant.parse("2027-08-10T00:00:00Z"), 2, 1, 1);
        cacheAuthenticator.seal(license);
        when(credentials.readToken()).thenReturn(Optional.empty());
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findActiveForSaasValidationForUpdate(
                store.getId(), installation.getId())).thenReturn(Optional.of(license));

        assertThatThrownBy(service::validateActiveLicense)
                .isInstanceOf(com.tpverp.backend.licensing.application.LicenseValidationException.class)
                .hasMessageContaining("falta su credencial");

        verify(client, never()).validate(org.mockito.Mockito.any());
    }

    private static Installation installation() {
        return new Installation("INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));
    }

    private static Store store() {
        var company = new Company("B12345678", "Company", address());
        return new Store(company, "Store", address(), "hash", "Atlantic/Canary", "EUR", "es-ES");
    }

    private static License license(Store store, Installation installation) {
        return new License(
                store,
                installation,
                "LIC-1",
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                1,
                0,
                "B12345678",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "{}",
                "hash",
                4,
                Instant.parse("2026-06-08T00:00:00Z"),
                Map.of(
                        "source", "SAAS_LINK",
                        "saasCompanyId", UUID.randomUUID().toString(),
                        "saasStoreId", UUID.randomUUID().toString()),
                ImportResult.ACEPTADA,
                null,
                true);
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
