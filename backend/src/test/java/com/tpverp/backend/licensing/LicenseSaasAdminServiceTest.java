package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LicenseSaasAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);
    private final LicenseSaasAdminService service = new LicenseSaasAdminService(
            licenses,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void bloqueaLicenciaManualmente() {
        var license = license();
        when(licenses.findByReferencia("LIC-1")).thenReturn(Optional.of(license));

        LicenseSaasValidationResponse response = service.block("LIC-1");

        assertThat(license.getEstadoSaas()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        assertThat(license.getUltimaValidacionSaas()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(LicenseSaasStatus.BLOQUEADA_MANUAL);
        verify(licenses).save(license);
    }

    @Test
    void desbloqueaLicenciaManualmente() {
        var license = license();
        license.markSaasBlocked(Instant.parse("2026-08-01T00:00:00Z"));
        when(licenses.findByReferencia("LIC-1")).thenReturn(Optional.of(license));

        LicenseSaasValidationResponse response = service.unblock("LIC-1");

        assertThat(license.getEstadoSaas()).isEqualTo(LicenseSaasStatus.VALIDA);
        assertThat(license.getUltimaValidacionSaas()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(LicenseSaasStatus.VALIDA);
        verify(licenses).save(license);
    }

    @Test
    void rechazaReferenciaInexistente() {
        when(licenses.findByReferencia("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.block("NOPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Licencia");
    }

    private static License license() {
        var company = new Company("B12345678", "Company", address());
        var store = new Store(company, "Store", address(), "hash", "Atlantic/Canary", "EUR", "es-ES");
        var installation = new Installation("INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));
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
