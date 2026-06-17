package com.tpverp.backend.verifactu;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class VerifactuAdminService {

    private final VerifactuSubmissionPropertiesFactory properties;
    private final VerifactuPkcs12KeyStoreLoader keyStores;
    private final VerifactuCertificateValidator certificates;
    private final FiscalSubmissionQueueService queue;
    private final VerifactuSubmissionWorker worker;
    private final Environment environment;
    private final VerifactuSignaturePolicy signatures;

    public VerifactuAdminService(
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuCertificateValidator certificates,
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            VerifactuSignaturePolicy signatures) {
        this(properties, keyStores, certificates, queue, worker, null, signatures);
    }

    @Autowired
    public VerifactuAdminService(
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuCertificateValidator certificates,
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            Environment environment,
            VerifactuSignaturePolicy signatures) {
        this.properties = properties;
        this.keyStores = keyStores;
        this.certificates = certificates;
        this.queue = queue;
        this.worker = worker;
        this.environment = environment;
        this.signatures = signatures;
    }

    public VerifactuAdminStatusView status() {
        try {
            var current = properties.current();
            var certificate = certificate(current);
            var status = certificates.validate(certificate);
            return new VerifactuAdminStatusView(
                    true, status.valid(), status.warning(), status.subject(),
                    status.notBefore(), status.notAfter(), current.mode(), workerEnabled(),
                    signatures.requiredForVerifactu(), signatures.mode());
        } catch (RuntimeException exception) {
            return new VerifactuAdminStatusView(
                    false, false, "CERTIFICATE_NOT_CONFIGURED",
                    null, null, null, null, workerEnabled(),
                    signatures.requiredForVerifactu(), signatures.mode());
        }
    }
    // Resume el estado operativo sin exponer password ni contenido del certificado.

    public List<FiscalSubmissionQueueItem> queue() {
        return queue.pending();
    }

    public VerifactuWorkerResult retryNext() {
        return worker.processNext();
    }

    private X509Certificate certificate(VerifactuSubmissionProperties current) {
        try {
            var keyStore = keyStores.load(current.certificatePath(), current.certificatePassword());
            var aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                var certificate = keyStore.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    return x509;
                }
            }
            throw new IllegalArgumentException("certificado X509 no encontrado");
        } catch (Exception exception) {
            throw new IllegalArgumentException("certificado no disponible", exception);
        }
    }

    private boolean workerEnabled() {
        return environment != null && environment.getProperty(
                "tpv.verifactu.worker-enabled", Boolean.class, false);
    }
}
