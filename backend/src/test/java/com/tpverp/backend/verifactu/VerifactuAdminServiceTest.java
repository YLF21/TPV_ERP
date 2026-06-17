package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VerifactuAdminServiceTest {

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
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001-260616-000001");
        when(queue.pending()).thenReturn(List.of(item));
        when(worker.processNext()).thenReturn(new VerifactuWorkerResult(
                true, FiscalSubmissionStatus.ACEPTADO, null, null));

        var service = service(queue, worker);

        assertThat(service.queue()).containsExactly(item);
        assertThat(service.retryNext().status()).isEqualTo(FiscalSubmissionStatus.ACEPTADO);
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
        var properties = Mockito.mock(VerifactuSubmissionPropertiesFactory.class);
        when(properties.current()).thenReturn(new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, Path.of("cert.p12"),
                "secret".toCharArray(), "TPV ERP", "01"));
        return new VerifactuAdminService(
                properties,
                Mockito.mock(VerifactuPkcs12KeyStoreLoader.class),
                certificateValidator(),
                queue,
                worker,
                new VerifactuSignaturePolicy());
    }

    private static VerifactuCertificateValidator certificateValidator() {
        var validator = Mockito.mock(VerifactuCertificateValidator.class);
        when(validator.validate(Mockito.any(X509Certificate.class)))
                .thenReturn(new VerifactuCertificateStatus(
                        true, null, "CN=Empresa", Instant.now(), Instant.now()));
        return validator;
    }
}
