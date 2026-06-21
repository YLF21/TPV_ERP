package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.Licencia;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuAdminService {

    private final VerifactuSubmissionPropertiesFactory properties;
    private final VerifactuPkcs12KeyStoreLoader keyStores;
    private final VerifactuCertificateValidator certificates;
    private final FiscalSubmissionQueueService queue;
    private final VerifactuSubmissionWorker worker;
    private final Environment environment;
    private final VerifactuSignaturePolicy signatures;
    private final VerifactuClockMonitor clock;
    private final VerifactuConfigurationRepository configurations;
    private final LicenciaRepository licenses;
    private final CurrentOrganization organization;
    private final VerifactuActivationService activation;
    private final Clock systemClock;
    private final FiscalSubmissionAttemptService attempts;

    public VerifactuAdminService(
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuCertificateValidator certificates,
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            VerifactuSignaturePolicy signatures) {
        this(properties, keyStores, certificates, queue, worker, null, signatures, null,
                null, null, null, null, null, null);
    }

    @Autowired
    public VerifactuAdminService(
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuCertificateValidator certificates,
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            Environment environment,
            VerifactuSignaturePolicy signatures,
            VerifactuClockMonitor clock,
            VerifactuConfigurationRepository configurations,
            LicenciaRepository licenses,
            CurrentOrganization organization,
            VerifactuActivationService activation,
            Clock systemClock,
            FiscalSubmissionAttemptService attempts) {
        this.properties = properties;
        this.keyStores = keyStores;
        this.certificates = certificates;
        this.queue = queue;
        this.worker = worker;
        this.environment = environment;
        this.signatures = signatures;
        this.clock = clock;
        this.configurations = configurations;
        this.licenses = licenses;
        this.organization = organization;
        this.activation = activation;
        this.systemClock = systemClock;
        this.attempts = attempts;
    }

    public VerifactuAdminStatusView status() {
        var activationStatus = activationStatus();
        try {
            var current = properties.current();
            var certificate = certificate(current);
            var status = certificates.validate(certificate);
            return new VerifactuAdminStatusView(
                    true, status.valid(), status.warning(), status.subject(),
                    status.notBefore(), status.notAfter(), current.mode(), workerEnabled(),
                    signatures.requiredForVerifactu(), signatures.mode(),
                    activationStatus.active(), activationStatus.mode(),
                    activationStatus.effectiveActivationAt(), activationStatus.firstSubmissionAt());
        } catch (RuntimeException exception) {
            return new VerifactuAdminStatusView(
                    false, false, "CERTIFICATE_NOT_CONFIGURED",
                    null, null, null, null, workerEnabled(),
                    signatures.requiredForVerifactu(), signatures.mode(),
                    activationStatus.active(), activationStatus.mode(),
                    activationStatus.effectiveActivationAt(), activationStatus.firstSubmissionAt());
        }
    }
    // Resume el estado operativo sin exponer password ni contenido del certificado.

    public List<FiscalSubmissionQueueItem> queue() {
        return queue.pending();
    }

    public VerifactuWorkerResult retryNext() {
        return worker.processNext();
    }

    public VerifactuClockStatusView clock() {
        if (clock == null) {
            throw new IllegalStateException("Monitor de hora VERI*FACTU no disponible");
        }
        return clock.current();
    }

    public List<FiscalSubmissionAttemptView> attempts(UUID recordId) {
        if (attempts == null) {
            throw new IllegalStateException("Historial de envios VERI*FACTU no disponible");
        }
        return attempts.history(recordId).stream()
                .map(FiscalSubmissionAttemptView::from)
                .toList();
    }

    @Transactional
    public VerifactuConfiguration activateVoluntary() {
        var configuration = currentConfiguration();
        configuration.activateVoluntarily(Instant.now(requiredClock()));
        return configurations.save(configuration);
    }
    // Activa VERI*FACTU de forma reversible hasta primera remision o fecha legal.

    @Transactional
    public VerifactuConfiguration deactivateVoluntary() {
        var configuration = currentConfiguration();
        var store = requiredOrganization().currentStore();
        activation.deactivateVoluntarily(
                configuration,
                activeLicense().getTaxpayerType(),
                Instant.now(requiredClock()),
                ZoneId.of(store.getTimezone()));
        return configurations.save(configuration);
    }
    // Desactiva solo si todavia no hay obligacion legal ni primera remision.

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

    private VerifactuActivationStatus activationStatus() {
        if (configurations == null || licenses == null || organization == null
                || activation == null || systemClock == null) {
            return VerifactuActivationStatus.unavailable();
        }
        var configuration = currentConfiguration();
        var license = activeLicense();
        var store = requiredOrganization().currentStore();
        var now = Instant.now(requiredClock());
        var zone = ZoneId.of(store.getTimezone());
        if (configuration.isVoluntarilyActive()) {
            return new VerifactuActivationStatus(
                    true, "VOLUNTARY", configuration.getActivatedAt(),
                    configuration.getFirstSubmissionAt());
        }
        if (activation.isLegallyRequired(license.getTaxpayerType(), now, zone)) {
            return new VerifactuActivationStatus(
                    true, "LEGAL",
                    activation.legalActivationInstant(license.getTaxpayerType(), zone),
                    configuration.getFirstSubmissionAt());
        }
        return new VerifactuActivationStatus(
                false, "INACTIVE", null, configuration.getFirstSubmissionAt());
    }

    private VerifactuConfiguration currentConfiguration() {
        var companyId = requiredOrganization().currentCompany().getId();
        configurations.insertIfMissing(UUID.randomUUID(), companyId);
        return configurations.findByCompanyId(companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la configuracion VERI*FACTU"));
    }

    private Licencia activeLicense() {
        var store = requiredOrganization().currentStore();
        return licenses.findByTiendaIdOrderByValidaDesdeDesc(store.getId()).stream()
                .filter(Licencia::isActiva)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No existe una licencia activa para la tienda e instalacion"));
    }

    private CurrentOrganization requiredOrganization() {
        if (organization == null) {
            throw new IllegalStateException("Organizacion actual no disponible");
        }
        return organization;
    }

    private Clock requiredClock() {
        if (systemClock == null) {
            throw new IllegalStateException("Reloj del sistema no disponible");
        }
        return systemClock;
    }
}
