package com.tpverp.backend.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.ImportResult;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.shared.access.OperationalMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstallationStatusServiceTest {

    private final InstallationRepository installations = org.mockito.Mockito.mock(InstallationRepository.class);
    private final LicenseRepository licenses = org.mockito.Mockito.mock(LicenseRepository.class);

    @Test
    void reportsUnlinkedWhenThereIsNoActiveLicense() {
        var service = serviceAt("2026-06-20T00:00:00Z");
        when(installations.findAll()).thenReturn(List.of(installation()));
        when(licenses.findAll()).thenReturn(List.of());

        var status = service.status();

        assertThat(status.mode()).isEqualTo(OperationalMode.UNLINKED);
        assertThat(status.activeLicenseReference()).isNull();
    }

    @Test
    void reportsRestrictedWhenActiveLicenseIsSaasBlocked() {
        var service = serviceAt("2026-08-20T00:00:00Z");
        var license = license("2027-07-31T23:59:59Z", "2026-08-01T00:00:00Z");
        license.markSaasBlocked(Instant.parse("2026-08-20T00:00:00Z"));
        when(installations.findAll()).thenReturn(List.of(installation()));
        when(licenses.findAll()).thenReturn(List.of(license));

        assertThat(service.status().mode()).isEqualTo(OperationalMode.RESTRICTED);
    }

    @Test
    void reportsOfflineWhenExpiredLicenseStillInsideOfflineGrace() {
        var service = serviceAt("2026-08-20T00:00:00Z");
        when(installations.findAll()).thenReturn(List.of(installation()));
        when(licenses.findAll()).thenReturn(List.of(license(
                "2026-07-31T23:59:59Z",
                "2026-08-01T00:00:00Z")));

        assertThat(service.status().mode()).isEqualTo(OperationalMode.OFFLINE);
    }

    @Test
    void bloqueaSiLaLicenciaEstaCaducadaYNoValidaConSaasHaceMasDeUnMes() {
        var service = serviceAt("2026-09-05T00:00:00Z");
        when(installations.findAll()).thenReturn(List.of(installation()));
        when(licenses.findAll()).thenReturn(List.of(license(
                "2026-07-31T23:59:59Z",
                "2026-08-01T00:00:00Z")));

        assertThat(service.status().mode()).isEqualTo(OperationalMode.RESTRICTED);
    }

    @Test
    void permiteTrabajarSiNoValidaHaceMasDeUnMesPeroLaLicenciaSigueVigente() {
        var service = serviceAt("2026-09-05T00:00:00Z");
        when(installations.findAll()).thenReturn(List.of(installation()));
        when(licenses.findAll()).thenReturn(List.of(license(
                "2026-12-31T23:59:59Z",
                "2026-08-01T00:00:00Z")));

        assertThat(service.status().mode()).isEqualTo(OperationalMode.LICENSED);
    }

    private InstallationStatusService serviceAt(String now) {
        return new InstallationStatusService(
                installations,
                licenses,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static Installation installation() {
        return new Installation("INST-1", "public-key", Instant.parse("2026-06-08T00:00:00Z"));
    }

    private static License license(String validUntil, String lastSaasValidationAt) {
        var company = new Company("B12345678", "Company", Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES"));
        var store = new Store(
                company,
                "Store",
                Map.of(
                        "linea1", "Calle Uno",
                        "ciudad", "Las Palmas",
                        "codigoPostal", "35001",
                        "provincia", "Las Palmas",
                        "pais", "ES"),
                "hash",
                "Atlantic/Canary",
                "EUR",
                "es-ES");
        var license = new License(
                store,
                installation(),
                "LIC-1",
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse(validUntil),
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
        license.markSaasValidated(Instant.parse(lastSaasValidationAt), license.getValidaHasta());
        return license;
    }
}
