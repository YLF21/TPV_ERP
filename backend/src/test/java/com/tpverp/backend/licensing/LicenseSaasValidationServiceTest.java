package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LicenseSaasValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private final InstallationRepository installations = org.mockito.Mockito.mock(InstallationRepository.class);
    private final StoreRepository stores = org.mockito.Mockito.mock(StoreRepository.class);
    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);
    private final LicenseSaasValidationClient client = org.mockito.Mockito.mock(LicenseSaasValidationClient.class);
    private final LicenseSaasValidationService service = new LicenseSaasValidationService(
            installations,
            stores,
            licenses,
            client,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void actualizaUltimaValidacionYVigenciaCuandoSaasDevuelveValida() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        when(installations.findAll()).thenReturn(List.of(installation));
        when(stores.findAll()).thenReturn(List.of(store));
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId()))
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
                        Instant.parse("2026-07-22T10:00:00Z")));

        service.validateActiveLicense();

        assertThat(license.getUltimaValidacionSaas()).isEqualTo(NOW);
        assertThat(license.getValidaHasta()).isEqualTo(Instant.parse("2027-08-10T00:00:00Z"));
        assertThat(license.getEstadoSaas()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(license.getVerifactuActivationDate()).isEqualTo(java.time.LocalDate.of(2027, 1, 1));
        assertThat(license.getVerifactuPolicyVersion()).isEqualTo(4L);
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
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId()))
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
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId()))
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
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId()))
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
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId()))
                .thenReturn(Optional.empty());

        service.validateActiveLicense();

        verify(client, never()).validate(org.mockito.Mockito.any());
        verify(licenses, never()).save(org.mockito.Mockito.any());
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
                3,
                Instant.parse("2026-06-08T00:00:00Z"),
                Map.of(),
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
