package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.ImportResult;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VerifactuAdminServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-19T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void devuelveEstadoConCertificadoNoConfiguradoSinRomper() {
        var properties = Mockito.mock(VerifactuSubmissionPropertiesFactory.class);
        when(properties.current()).thenThrow(new IllegalArgumentException("certificado obligatorio"));

        var status = service(properties).status();

        assertThat(status.certificateConfigured()).isFalse();
        assertThat(status.certificateValid()).isFalse();
        assertThat(status.warning()).isEqualTo("CERTIFICATE_NOT_CONFIGURED");
        assertThat(status.signatureRequired()).isFalse();
    }

    @Test
    void listaColaYReintentaSiguienteRegistro() {
        var queue = Mockito.mock(FiscalSubmissionQueueService.class);
        var worker = Mockito.mock(VerifactuSubmissionWorker.class);
        var item = new FiscalSubmissionQueueItem(
                UUID.randomUUID(), 1, FiscalSubmissionStatus.PENDIENTE,
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001-260616-000001",
                null, null, Instant.parse("2026-06-16T10:00:00Z"));
        when(queue.pending()).thenReturn(List.of(item));
        when(worker.processNext()).thenReturn(new VerifactuWorkerResult(
                true, FiscalSubmissionStatus.ACEPTADO, null, null));

        var service = service(queue, worker);

        assertThat(service.queue()).containsExactly(item);
        assertThat(service.retryNext().status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
    }

    @Test
    void exponeHistorialDeIntentosDeUnRegistro() {
        var attempts = Mockito.mock(FiscalSubmissionAttemptService.class);
        var service = service(attempts);
        var recordId = UUID.randomUUID();
        var attempt = new FiscalSubmissionAttempt(
                recordId,
                Instant.parse("2026-06-16T10:00:00Z"),
                FiscalSubmissionStatus.RECHAZADO,
                "NIF_INVALIDO",
                "NIF incorrecto",
                null,
                "<response/>");
        when(attempts.history(recordId)).thenReturn(List.of(attempt));

        var result = service.attempts(recordId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo(FiscalSubmissionStatus.RECHAZADO);
        assertThat(result.getFirst().errorCode()).isEqualTo("NIF_INVALIDO");
        assertThat(result.getFirst().responsePayload()).isEqualTo("<response/>");
        assertThat(FiscalSubmissionAttemptView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("requestXml");
    }

    @Test
    void exponeEstadoDeHoraDelServidor() {
        var monitor = Mockito.mock(VerifactuClockMonitor.class);
        var status = new VerifactuClockStatusView(
                true, "CLOCK_DRIFT_OVER_5_MINUTES",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(301),
                301, 300, Instant.EPOCH);
        when(monitor.current()).thenReturn(status);

        assertThat(service(monitor).clock()).isSameAs(status);
    }

    @Test
    void activaYDesactivaModoVoluntarioConLaLicenciaActiva() {
        var dependencies = activationDependencies(TaxpayerType.SOCIEDAD);
        var service = service(dependencies);

        var activated = service.activateVoluntary();
        assertThat(activated.isVoluntarilyActive()).isTrue();
        assertThat(activated.getActivatedAt()).isEqualTo(CLOCK.instant());

        var deactivated = service.deactivateVoluntary();
        assertThat(deactivated.isVoluntarilyActive()).isFalse();
        Mockito.verify(dependencies.configurations(), Mockito.times(2))
                .save(dependencies.configuration());
    }

    @Test
    void statusIncluyeActivacionVoluntaria() {
        var dependencies = activationDependencies(TaxpayerType.SOCIEDAD);
        dependencies.configuration().activateVoluntarily(CLOCK.instant());

        var status = service(dependencies, configuredProperties()).status();

        assertThat(status.verifactuActive()).isTrue();
        assertThat(status.activationMode()).isEqualTo("VOLUNTARY");
        assertThat(status.effectiveActivationAt()).isEqualTo(CLOCK.instant());
        assertThat(status.firstSubmissionAt()).isNull();
    }

    @Test
    void statusIncluyeActivacionLegalAunqueFalteCertificado() {
        var dependencies = activationDependencies(TaxpayerType.SOCIEDAD);
        var properties = Mockito.mock(VerifactuSubmissionPropertiesFactory.class);
        when(properties.current()).thenThrow(new IllegalArgumentException("certificado obligatorio"));
        var service = service(
                dependencies,
                properties,
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));

        var status = service.status();

        assertThat(status.certificateConfigured()).isFalse();
        assertThat(status.verifactuActive()).isTrue();
        assertThat(status.activationMode()).isEqualTo("LEGAL_FALLBACK");
        assertThat(status.effectiveActivationAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void statusUsaElCertificadoActivoGestionado() {
        var dependencies = activationDependencies(TaxpayerType.SOCIEDAD);
        var repository = Mockito.mock(ManagedVerifactuCertificateRepository.class);
        var certificate = ManagedVerifactuCertificate.active(
                dependencies.organization().currentCompany().getId(),
                "CN=Company", "CN=CA", "123", "B00000000",
                CLOCK.instant().minusSeconds(60), CLOCK.instant().plusSeconds(3600),
                "A".repeat(64), new byte[]{1}, "secret.bin", CLOCK.instant(),
                UUID.randomUUID());
        when(repository.findByCompanyIdAndStatus(
                dependencies.organization().currentCompany().getId(),
                ManagedCertificateStatus.ACTIVO)).thenReturn(Optional.of(certificate));

        var service = new VerifactuAdminService(
                configuredProperties(), Mockito.mock(VerifactuPkcs12KeyStoreLoader.class),
                certificateValidator(), Mockito.mock(FiscalSubmissionQueueService.class),
                Mockito.mock(VerifactuSubmissionWorker.class), null,
                new VerifactuSignaturePolicy(), null, dependencies.configurations(),
                dependencies.licenses(), dependencies.organization(),
                new VerifactuActivationService(), CLOCK, null, repository);

        var status = service.status();

        assertThat(status.certificateConfigured()).isTrue();
        assertThat(status.certificateValid()).isTrue();
        assertThat(status.certificateSubject()).isEqualTo("CN=Company");
        assertThat(status.endpointMode()).isEqualTo(VerifactuEndpointMode.TEST);
    }

    private static VerifactuAdminService service(VerifactuSubmissionPropertiesFactory properties) {
        return new VerifactuAdminService(
                properties,
                Mockito.mock(VerifactuPkcs12KeyStoreLoader.class),
                Mockito.mock(VerifactuCertificateValidator.class),
                Mockito.mock(FiscalSubmissionQueueService.class),
                Mockito.mock(VerifactuSubmissionWorker.class),
                new VerifactuSignaturePolicy());
    }

    private static VerifactuAdminService service(
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker) {
        return service(queue, worker, null);
    }

    private static VerifactuAdminService service(VerifactuClockMonitor monitor) {
        return service(
                Mockito.mock(FiscalSubmissionQueueService.class),
                Mockito.mock(VerifactuSubmissionWorker.class),
                monitor);
    }

    private static VerifactuAdminService service(FiscalSubmissionAttemptService attempts) {
        return service(
                Mockito.mock(FiscalSubmissionQueueService.class),
                Mockito.mock(VerifactuSubmissionWorker.class),
                null,
                attempts);
    }

    private static VerifactuAdminService service(ActivationDependencies dependencies) {
        return service(dependencies, Mockito.mock(VerifactuSubmissionPropertiesFactory.class));
    }

    private static VerifactuAdminService service(
            ActivationDependencies dependencies,
            VerifactuSubmissionPropertiesFactory properties) {
        return service(dependencies, properties, CLOCK);
    }

    private static VerifactuAdminService service(
            ActivationDependencies dependencies,
            VerifactuSubmissionPropertiesFactory properties,
            Clock clock) {
        return new VerifactuAdminService(
                properties,
                Mockito.mock(VerifactuPkcs12KeyStoreLoader.class),
                certificateValidator(),
                Mockito.mock(FiscalSubmissionQueueService.class),
                Mockito.mock(VerifactuSubmissionWorker.class),
                null,
                new VerifactuSignaturePolicy(),
                null,
                dependencies.configurations(),
                dependencies.licenses(),
                dependencies.organization(),
                new VerifactuActivationService(),
                clock,
                null,
                null);
    }

    private static VerifactuAdminService service(
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            VerifactuClockMonitor monitor) {
        return service(queue, worker, monitor, null);
    }

    private static VerifactuAdminService service(
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            VerifactuClockMonitor monitor,
            FiscalSubmissionAttemptService attempts) {
        var properties = Mockito.mock(VerifactuSubmissionPropertiesFactory.class);
        when(properties.current()).thenReturn(new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, "TPV ERP", "01"));
        return new VerifactuAdminService(
                properties,
                Mockito.mock(VerifactuPkcs12KeyStoreLoader.class),
                certificateValidator(),
                queue,
                worker,
                null,
                new VerifactuSignaturePolicy(),
                monitor,
                null,
                null,
                null,
                null,
                null,
                attempts,
                null);
    }

    private static VerifactuCertificateValidator certificateValidator() {
        var validator = Mockito.mock(VerifactuCertificateValidator.class);
        when(validator.validate(Mockito.any()))
                .thenReturn(new VerifactuCertificateStatus(
                        true, null, "CN=Company", Instant.now(), Instant.now()));
        return validator;
    }

    private static VerifactuSubmissionPropertiesFactory configuredProperties() {
        var properties = Mockito.mock(VerifactuSubmissionPropertiesFactory.class);
        when(properties.current()).thenReturn(new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, "TPV ERP", "01"));
        return properties;
    }

    private static ActivationDependencies activationDependencies(TaxpayerType taxpayerType) {
        var company = new Company("B00000000", "Company", address());
        var store = new Store(company, "Store", address(), "001", "Atlantic/Canary", "EUR", "es-ES");
        var organization = Mockito.mock(CurrentOrganization.class);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        var configuration = new VerifactuConfiguration(company.getId());
        var configurations = Mockito.mock(VerifactuConfigurationRepository.class);
        when(configurations.findByCompanyId(company.getId())).thenReturn(Optional.of(configuration));
        when(configurations.findForUpdateByCompanyId(company.getId()))
                .thenReturn(Optional.of(configuration));
        when(configurations.save(configuration)).thenReturn(configuration);
        var licenses = Mockito.mock(LicenseRepository.class);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(store.getId())).thenReturn(List.of(
                license(store, taxpayerType)));
        return new ActivationDependencies(
                organization, configurations, licenses, configuration);
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

    private record ActivationDependencies(
            CurrentOrganization organization,
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            VerifactuConfiguration configuration) {
    }
}
