package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class VerifactuAdminReadServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Mock private CurrentOrganization organization;
    @Mock private VerifactuAdminReadRepository reads;
    @Mock private VerifactuConfigurationRepository configurations;
    @Mock private LicenseRepository licenses;
    @Mock private InstallationRepository installations;
    @Mock private VerifactuActivationService activation;
    @Mock private ManagedVerifactuCertificateRepository certificates;
    @Mock private VerifactuSubmissionPropertiesFactory properties;
    @Mock private VerifactuClockMonitor clockMonitor;
    @Mock private Environment environment;
    @Mock private Store store;
    @Mock private Company company;

    private VerifactuAdminReadService service;

    @BeforeEach
    void setUp() {
        service = new VerifactuAdminReadService(
                organization, reads, configurations, licenses, activation,
                certificates, properties, clockMonitor, environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void summaryIsStoreScopedReadOnlySanitizedAndCompletesMissingCounts() {
        scope();
        var configuration = mock(VerifactuConfiguration.class);
        when(configuration.isVoluntarilyActive()).thenReturn(true);
        when(configuration.getActivatedAt()).thenReturn(NOW.minusSeconds(3_600));
        when(configuration.getFirstSubmissionAt()).thenReturn(NOW.minusSeconds(1_800));
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(configuration));
        when(reads.countByStatus(COMPANY_ID, STORE_ID)).thenReturn(Map.of(
                FiscalSubmissionStatus.PENDIENTE, 2L,
                FiscalSubmissionStatus.RECHAZADO, 1L));
        when(reads.findOldestPendingAt(COMPANY_ID, STORE_ID))
                .thenReturn(NOW.minusSeconds(900));
        var certificate = mock(ManagedVerifactuCertificate.class);
        when(certificate.getValidFrom()).thenReturn(NOW.minusSeconds(86_400));
        when(certificate.getValidUntil()).thenReturn(NOW.plusSeconds(86_400));
        when(certificates.findByCompanyIdAndStatus(COMPANY_ID, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(certificate));
        when(properties.current()).thenReturn(new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, "TPV ERP", "TPVERP"));
        when(environment.getProperty("tpv.verifactu.worker-enabled", Boolean.class, false))
                .thenReturn(true);
        when(clockMonitor.current()).thenReturn(new VerifactuClockStatusView(
                false, null, NOW, NOW, 0, 30, NOW));

        var result = service.summary();

        assertThat(result.active()).isTrue();
        assertThat(result.activationMode()).isEqualTo("VOLUNTARY");
        assertThat(result.endpointMode()).isEqualTo(VerifactuEndpointMode.TEST);
        assertThat(result.workerEnabled()).isTrue();
        assertThat(result.countsByStatus()).hasSize(FiscalSubmissionStatus.values().length);
        assertThat(result.countsByStatus().get(FiscalSubmissionStatus.PENDIENTE)).isEqualTo(2L);
        assertThat(result.countsByStatus().get(FiscalSubmissionStatus.ACEPTADO)).isZero();
        assertThat(result.certificate()).isEqualTo(new VerifactuAdminCertificateSummary(
                true, true, null, NOW.plusSeconds(86_400)));
        assertThat(result.clock().available()).isTrue();
        verify(reads).countByStatus(COMPANY_ID, STORE_ID);
        verify(reads).findOldestPendingAt(COMPANY_ID, STORE_ID);
        verify(configurations, never()).insertIfMissing(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void summaryExposesFutureLicenseActivationAsScheduledWithoutActivating() {
        scope();
        var activationDate = LocalDate.of(2026, 8, 27);
        var effectiveAt = Instant.parse("2026-08-27T00:00:00Z");
        var license = mock(License.class);
        when(license.isActiva()).thenReturn(true);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(activationDate);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(STORE_ID))
                .thenReturn(List.of(license));
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        when(activation.activationInstant(TaxpayerType.SOCIEDAD, activationDate,
                ZoneId.of("Atlantic/Canary"))).thenReturn(effectiveAt);

        var result = service.summary();

        assertThat(result.active()).isFalse();
        assertThat(result.activationMode()).isEqualTo("LICENSE_SCHEDULED");
        assertThat(result.effectiveActivationAt()).isEqualTo(effectiveAt);
    }

    @Test
    void summaryMarksExpiredLicensePolicyAsActive() {
        scope();
        var activationDate = LocalDate.of(2026, 7, 20);
        var effectiveAt = Instant.parse("2026-07-20T00:00:00Z");
        var license = mock(License.class);
        when(license.isActiva()).thenReturn(true);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(activationDate);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(STORE_ID))
                .thenReturn(List.of(license));
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        when(activation.activationInstant(TaxpayerType.SOCIEDAD, activationDate,
                ZoneId.of("Atlantic/Canary"))).thenReturn(effectiveAt);

        var result = service.summary();

        assertThat(result.active()).isTrue();
        assertThat(result.activationMode()).isEqualTo("LICENSE_POLICY");
        assertThat(result.effectiveActivationAt()).isEqualTo(effectiveAt);
    }

    @Test
    void summaryKeepsLicenseWithoutExplicitActivationDateInactive() {
        scope();
        var license = mock(License.class);
        when(license.isActiva()).thenReturn(true);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getVerifactuActivationDate()).thenReturn(null);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(STORE_ID))
                .thenReturn(List.of(license));
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        var result = service.summary();

        assertThat(result.active()).isFalse();
        assertThat(result.activationMode()).isEqualTo("INACTIVE");
        assertThat(result.effectiveActivationAt()).isNull();
        verify(activation, never()).activationInstant(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void submissionsAreScopedToResolvedFiscalInstallation() {
        scope();
        var license = mock(License.class);
        var installation = mock(Installation.class);
        when(license.getLocalCompanyId()).thenReturn(COMPANY_ID);
        when(license.getInstalacionId()).thenReturn(INSTALLATION_ID);
        when(licenses.findActiveByTiendaId(STORE_ID)).thenReturn(List.of(license));
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        var expected = new VerifactuAdminSubmissionPage(List.of(), 0, 25, 0, 0);
        when(reads.findSubmissions(COMPANY_ID, STORE_ID, INSTALLATION_ID,
                null, null, null, null, null, null, 0, 25)).thenReturn(expected);

        var scopedService = new VerifactuAdminReadService(
                organization, installations, reads, configurations, licenses, activation,
                certificates, properties, clockMonitor, environment,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(scopedService.submissions(null, null, null, null, null, null, 0, 25))
                .isSameAs(expected);
        verify(reads).findSubmissions(COMPANY_ID, STORE_ID, INSTALLATION_ID,
                null, null, null, null, null, null, 0, 25);
    }

    @Test
    void submissionsConvertsInclusiveStoreDatesToExclusiveInstants() {
        scope();
        var expected = new VerifactuAdminSubmissionPage(List.of(), 2, 25, 0, 0);
        when(reads.findSubmissions(
                COMPANY_ID,
                STORE_ID,
                Instant.parse("2026-06-30T23:00:00Z"),
                Instant.parse("2026-07-31T23:00:00Z"),
                FiscalSubmissionStatus.RECHAZADO,
                FiscalDocumentType.F2,
                FiscalRecordOperation.ALTA,
                "T-100",
                2,
                25)).thenReturn(expected);

        var result = service.submissions(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                FiscalSubmissionStatus.RECHAZADO,
                FiscalDocumentType.F2,
                FiscalRecordOperation.ALTA,
                "  T-100  ",
                2,
                25);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void rejectsInvalidFiltersBeforeResolvingOrganization() {
        assertThatThrownBy(() -> service.submissions(
                LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1),
                null, null, null, null, 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFrom");
        assertThatThrownBy(() -> service.submissions(
                null, null, null, null, null, null, -1, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
        assertThatThrownBy(() -> service.submissions(
                null, null, null, null, null, "X".repeat(65), 0, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentNumber");

        verify(organization, never()).currentStore();
    }

    @Test
    void unavailableClockIsReportedWithoutLeakingTheFailure() {
        scope();
        when(configurations.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(STORE_ID)).thenReturn(List.of());
        when(reads.countByStatus(COMPANY_ID, STORE_ID)).thenReturn(Map.of());
        when(certificates.findByCompanyIdAndStatus(COMPANY_ID, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.empty());
        when(clockMonitor.current()).thenThrow(new IllegalStateException("database host secret"));

        var result = service.summary();

        assertThat(result.activationMode()).isEqualTo("UNAVAILABLE");
        assertThat(result.certificate().warningCode()).isEqualTo("CERTIFICATE_NOT_CONFIGURED");
        assertThat(result.clock()).isEqualTo(new VerifactuAdminClockSummary(
                false, true, "CLOCK_STATUS_UNAVAILABLE", null, null, null));
    }

    private void scope() {
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE_ID);
        when(store.getEmpresa()).thenReturn(company);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(company.getId()).thenReturn(COMPANY_ID);
    }
}
