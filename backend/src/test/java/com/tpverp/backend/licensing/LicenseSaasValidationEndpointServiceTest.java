package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LicenseSaasValidationEndpointServiceTest {

    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);
    private final LicenseSaasValidationEndpointService service =
            new LicenseSaasValidationEndpointService(licenses);

    @Test
    void devuelveValidaParaLicenciaActivaDeLaInstalacionYTienda() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        when(licenses.findByReferencia("LIC-1")).thenReturn(Optional.of(license));

        LicenseSaasValidationResponse response = service.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                "LIC-1",
                "hash"));

        assertThat(response.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(response.validUntil()).isEqualTo(license.getValidaHasta());
    }

    @Test
    void devuelveBloqueadaManualSiLaLicenciaFueBloqueadaEnCentral() {
        var installation = installation();
        var store = store();
        var license = license(store, installation);
        license.markSaasBlocked(Instant.parse("2026-08-10T00:00:00Z"));
        when(licenses.findByReferencia("LIC-1")).thenReturn(Optional.of(license));

        LicenseSaasValidationResponse response = service.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                "LIC-1",
                "hash"));

        assertThat(response.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(response.validUntil()).isEqualTo(license.getValidaHasta());
    }

    @Test
    void rechazaReferenciaDeOtraInstalacionOTienda() {
        var installation = installation();
        var store = store();
        when(licenses.findByReferencia("LIC-1")).thenReturn(Optional.of(license(store, installation)));

        assertThatThrownBy(() -> service.validate(new LicenseSaasValidationRequest(
                UUID.randomUUID(),
                installation.getReferencia(),
                store.getId(),
                "LIC-1",
                "hash")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("licencia");
    }

    @Test
    void rechazaHashDeLicenciaIncorrecto() {
        var installation = installation();
        var store = store();
        when(licenses.findByReferencia("LIC-1")).thenReturn(Optional.of(license(store, installation)));

        assertThatThrownBy(() -> service.validate(new LicenseSaasValidationRequest(
                installation.getId(),
                installation.getReferencia(),
                store.getId(),
                "LIC-1",
                "otro-hash")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("licencia");
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
                Instant.parse("2027-08-10T00:00:00Z"),
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
