package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.ImportResult;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VerifactuFirstSubmissionPermanenceTest {

    @Test
    void fijaHastaFinDelMismoAnoSoloEnRuntimeRealParaSociedad() {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(eq(store.getId()), any()))
                .thenReturn(Optional.of(license(store)));
        var marker = new VerifactuFirstSubmissionMarker(
                configurations, licenses, new VerifactuActivationService(),
                Clock.fixed(Instant.parse("2027-01-01T00:30:00Z"), ZoneOffset.UTC));
        marker.setRuntimeProperties(new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")));

        marker.mark(record(company.getId(), store.getId()));

        assertThat(configuration.getVerifactuBlockedUntil()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void fijaHastaFinDelMismoAnoParaAutonomoCuandoYaRigeLaAdaptacionSif() {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(eq(store.getId()), any()))
                .thenReturn(Optional.of(license(store, TaxpayerType.AUTONOMO)));
        var marker = new VerifactuFirstSubmissionMarker(
                configurations, licenses, new VerifactuActivationService(),
                Clock.fixed(Instant.parse("2027-07-01T00:30:00Z"), ZoneOffset.UTC));
        marker.setRuntimeProperties(new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")));

        marker.mark(record(company.getId(), store.getId()));

        assertThat(configuration.getVerifactuBlockedUntil()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void noCreaPermanenciaDuranteElPeriodoDePruebas2026() {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(eq(store.getId()), any()))
                .thenReturn(Optional.of(license(store, TaxpayerType.SOCIEDAD)));
        var marker = new VerifactuFirstSubmissionMarker(
                configurations, licenses, new VerifactuActivationService(),
                Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneOffset.UTC));
        marker.setRuntimeProperties(new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")));

        marker.mark(record(company.getId(), store.getId()));

        assertThat(configuration.getVerifactuBlockedUntil()).isNull();
    }

    @Test
    void unAckPosteriorAlPrimerEnvioDe2026CreaLaPermanenciaDel2027() {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.activateVoluntarily(Instant.parse("2026-06-01T00:00:00Z"));
        configuration.markFirstSubmission(Instant.parse("2026-06-01T00:05:00Z"), null);
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(eq(store.getId()), any()))
                .thenReturn(Optional.of(license(store, TaxpayerType.SOCIEDAD)));
        var marker = new VerifactuFirstSubmissionMarker(
                configurations, licenses, new VerifactuActivationService(),
                Clock.fixed(Instant.parse("2027-01-02T00:30:00Z"), ZoneOffset.UTC));
        marker.setRuntimeProperties(new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")));

        marker.mark(record(company.getId(), store.getId()));

        assertThat(configuration.getFirstSubmissionAt())
                .isEqualTo(Instant.parse("2026-06-01T00:05:00Z"));
        assertThat(configuration.getVerifactuBlockedUntil()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void unAckDeEneroDe2028AmpliaLaPermanenciaAl2028() {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        configuration.activateVoluntarily(Instant.parse("2026-06-01T00:00:00Z"));
        configuration.markFirstSubmission(Instant.parse("2026-06-01T00:05:00Z"), null);
        configuration.lockVerifactuUntil(LocalDate.of(2027, 12, 31));
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(eq(store.getId()), any()))
                .thenReturn(Optional.of(license(store, TaxpayerType.SOCIEDAD)));
        var marker = new VerifactuFirstSubmissionMarker(
                configurations, licenses, new VerifactuActivationService(),
                Clock.fixed(Instant.parse("2028-01-02T00:30:00Z"), ZoneOffset.UTC));
        marker.setRuntimeProperties(new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")));

        marker.mark(record(company.getId(), store.getId()));

        assertThat(configuration.getVerifactuBlockedUntil()).isEqualTo(LocalDate.of(2028, 12, 31));
    }

    @Test
    void runtimeSandboxNoCreaPermanenciaAunqueYaRijaLaAdaptacion() {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = mock(VerifactuConfigurationRepository.class);
        when(configurations.findForUpdateByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(eq(store.getId()), any()))
                .thenReturn(Optional.of(license(store, TaxpayerType.SOCIEDAD)));
        var marker = new VerifactuFirstSubmissionMarker(
                configurations, licenses, new VerifactuActivationService(),
                Clock.fixed(Instant.parse("2027-01-02T00:30:00Z"), ZoneOffset.UTC));
        marker.setRuntimeProperties(new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED")));

        marker.mark(record(company.getId(), store.getId()));

        assertThat(configuration.getVerifactuBlockedUntil()).isNull();
    }

    private static FiscalRecord record(UUID companyId, UUID storeId) {
        return new FiscalRecord(UUID.randomUUID(), companyId, UUID.randomUUID(), storeId,
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-270101-00001", LocalDate.of(2027, 1, 1),
                Instant.parse("2027-01-01T00:30:00Z"), "Atlantic/Canary", "B12345674",
                new BigDecimal("2.10"), new BigDecimal("12.10"), null,
                "A".repeat(64), "B".repeat(64), Map.of("total", "12.10"),
                "1.0", "SHA-256", "0.0.1");
    }

    private static License license(Store store) {
        return license(store, TaxpayerType.SOCIEDAD);
    }

    private static License license(Store store, TaxpayerType taxpayerType) {
        var license = new License(store,
                new com.tpverp.backend.installation.Installation(
                        "PUBLIC", "PRIVATE", Instant.parse("2026-01-01T00:00:00Z")),
                "LIC-1", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2028-01-01T00:00:00Z"), 1, 0, "B00000000",
                taxpayerType, TaxRegime.IVA, "blob", "hash", 1,
                Instant.parse("2026-01-01T00:00:00Z"), Map.of(), ImportResult.ACEPTADA,
                null, true);
        license.applyVerifactuPolicy(LocalDate.of(2026, 1, 1), 1,
                Instant.parse("2026-01-01T00:00:00Z"));
        return license;
    }

    private static Map<String, String> address() {
        return Map.of("linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    }
}
