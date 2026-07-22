package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
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
    private final LicenseRepository licenses;
    private final CurrentOrganization organization;
    private final VerifactuActivationService activation;
    private final Clock systemClock;
    private final FiscalSubmissionAttemptService attempts;
    private final ManagedVerifactuCertificateRepository managedCertificates;

    public VerifactuAdminService(
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuCertificateValidator certificates,
            FiscalSubmissionQueueService queue,
            VerifactuSubmissionWorker worker,
            VerifactuSignaturePolicy signatures) {
        this(properties, keyStores, certificates, queue, worker, null, signatures, null,
                null, null, null, null, null, null, null);
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
            LicenseRepository licenses,
            CurrentOrganization organization,
            VerifactuActivationService activation,
            Clock systemClock,
            FiscalSubmissionAttemptService attempts,
            ManagedVerifactuCertificateRepository managedCertificates) {
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
        this.managedCertificates = managedCertificates;
    }

    public VerifactuAdminStatusView status() {
        var activationStatus = activationStatus();
        try {
            var current = properties.current();
            var certificate = activeCertificate();
            var now = Instant.now(requiredClock());
            var valid = !now.isBefore(certificate.getValidFrom())
                    && !now.isAfter(certificate.getValidUntil());
            var warning = now.isAfter(certificate.getValidUntil())
                    ? "CERTIFICATE_EXPIRED"
                    : now.isBefore(certificate.getValidFrom())
                            ? "CERTIFICATE_NOT_YET_VALID" : null;
            return new VerifactuAdminStatusView(
                    true, valid, warning, certificate.getSubject(),
                    certificate.getValidFrom(), certificate.getValidUntil(),
                    current.mode(), workerEnabled(),
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
        var configuration = currentConfigurationForUpdate();
        configuration.activateVoluntarily(Instant.now(requiredClock()));
        return configurations.save(configuration);
    }
    // Activa VERI*FACTU de forma reversible hasta primera remision o fecha legal.

    @Transactional
    public VerifactuConfiguration deactivateVoluntary() {
        var configuration = currentConfigurationForUpdate();
        var store = requiredOrganization().currentStore();
        var license = activeLicense();
        activation.deactivateVoluntarily(
                configuration,
                license.getTaxpayerType(),
                license.getVerifactuActivationDate(),
                Instant.now(requiredClock()),
                ZoneId.of(store.getTimezone()));
        return configurations.save(configuration);
    }
    // Desactiva solo si todavia no hay obligacion legal ni primera remision.

    private ManagedVerifactuCertificate activeCertificate() {
        if (managedCertificates == null) {
            throw new IllegalStateException("Repositorio de certificados no disponible");
        }
        return managedCertificates.findByCompanyIdAndStatus(
                        requiredOrganization().currentCompany().getId(),
                        ManagedCertificateStatus.ACTIVO)
                .orElseThrow(() -> new IllegalStateException("Certificado no configurado"));
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
        if (configuration.getFirstSubmissionAt() != null) {
            return new VerifactuActivationStatus(
                    true, "LOCKED", configuration.getActivatedAt(),
                    configuration.getFirstSubmissionAt());
        }
        if (activation.isAutomaticallyRequired(
                license.getTaxpayerType(),
                license.getVerifactuActivationDate(),
                now,
                zone)) {
            return new VerifactuActivationStatus(
                    true,
                    license.getVerifactuActivationDate() == null ? "LEGAL_FALLBACK" : "LICENSE_POLICY",
                    activation.activationInstant(
                            license.getTaxpayerType(),
                            license.getVerifactuActivationDate(),
                            zone),
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

    private VerifactuConfiguration currentConfigurationForUpdate() {
        var companyId = requiredOrganization().currentCompany().getId();
        configurations.insertIfMissing(UUID.randomUUID(), companyId);
        return configurations.findForUpdateByCompanyId(companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo bloquear la configuracion VERI*FACTU"));
    }

    private License activeLicense() {
        var store = requiredOrganization().currentStore();
        return licenses.findByTiendaIdOrderByValidaDesdeDesc(store.getId()).stream()
                .filter(License::isActiva)
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
