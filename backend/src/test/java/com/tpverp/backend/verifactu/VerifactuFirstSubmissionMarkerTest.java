package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VerifactuFirstSubmissionMarkerTest {

    @Test
    void marcaPrimeraRemisionConLaFechaDelRegistroAceptado() {
        var setup = setup(TaxpayerType.SOCIEDAD);
        var record = record(setup.company().getId(), setup.store().getId());

        new VerifactuFirstSubmissionMarker(
                setup.configurations(), setup.licenses(), new VerifactuActivationService())
                .mark(record);

        assertThat(setup.configuration().getFirstSubmissionAt()).isEqualTo(record.getGeneratedAt());
        assertThat(setup.configuration().getActivatedAt()).isEqualTo(record.getGeneratedAt());
        verify(setup.configurations()).save(setup.configuration());
    }

    @Test
    void noSobrescribePrimeraRemisionYaRegistrada() {
        var setup = setup(TaxpayerType.SOCIEDAD);
        setup.configuration().activateVoluntarily(Instant.parse("2026-06-01T00:00:00Z"));
        setup.configuration().markFirstSubmission(
                Instant.parse("2026-06-01T00:05:00Z"), null);

        new VerifactuFirstSubmissionMarker(
                setup.configurations(), setup.licenses(), new VerifactuActivationService())
                .mark(record(setup.company().getId(), setup.store().getId()));

        verify(setup.configurations(), never()).save(setup.configuration());
    }

    private static Setup setup(TaxpayerType taxpayerType) {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = Mockito.mock(VerifactuConfigurationRepository.class);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        var licenses = Mockito.mock(LicenseRepository.class);
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                Mockito.eq(store.getId()), Mockito.any()))
                .thenReturn(Optional.of(license(store, taxpayerType)));
        return new Setup(company, store, configuration, configurations, licenses);
    }

    private static FiscalRecord record(UUID companyId, UUID storeId) {
        return new FiscalRecord(
                UUID.randomUUID(), companyId, UUID.randomUUID(), storeId,
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-270101-00001", LocalDate.of(2027, 1, 1),
                Instant.parse("2027-01-01T00:00:00Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                null, "A".repeat(64), "B".repeat(64), Map.of("total", "12.10"),
                "1.0", "SHA-256", "0.0.1");
    }

    private static License license(Store store, TaxpayerType taxpayerType) {
        return new License(
                store,
                new com.tpverp.backend.installation.Installation(
                        "PUBLIC", "PRIVATE", Instant.parse("2026-01-01T00:00:00Z")),
                "LIC-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2028-01-01T00:00:00Z"),
                1,
                0,
                "B00000000",
                taxpayerType,
                TaxRegime.IVA,
                "blob",
                "hash",
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of(),
                ImportResult.ACEPTADA,
                null,
                true);
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }

    private record Setup(
            Company company,
            Store store,
            VerifactuConfiguration configuration,
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses) {
    }
}
