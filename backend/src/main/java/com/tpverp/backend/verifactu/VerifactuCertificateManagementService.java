package com.tpverp.backend.verifactu;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class VerifactuCertificateManagementService {

    private final ManagedVerifactuCertificateRepository certificates;
    private final VerifactuCertificateImporter importer;
    private final VerifactuCertificateSecretStore secrets;
    private final CurrentOrganization organization;
    private final AuditService audit;
    private final Clock clock;

    public VerifactuCertificateManagementService(
            ManagedVerifactuCertificateRepository certificates,
            VerifactuCertificateImporter importer,
            VerifactuCertificateSecretStore secrets,
            CurrentOrganization organization,
            AuditService audit,
            Clock clock) {
        this.certificates = certificates;
        this.importer = importer;
        this.secrets = secrets;
        this.organization = organization;
        this.audit = audit;
        this.clock = clock;
    }

    // Importa o sustituye el certificado conservando solo una clave anterior.
    @Transactional
    public ManagedCertificateView importCertificate(
            byte[] pkcs12, char[] password, Authentication authentication) {
        var company = organization.currentCompany();
        var userId = organization.currentUser(authentication).getId();
        var certificateId = UUID.randomUUID();
        String newSecret = null;
        try {
            var material = importer.importPkcs12(pkcs12, password, company.getTaxId());
            newSecret = secrets.write(
                    company.getId(), certificateId, material.privateKeyPkcs8());
            registerRollbackCleanup(newSecret);
            var now = Instant.now(clock);
            var active = certificates.findByCompanyIdAndStatus(
                    company.getId(), ManagedCertificateStatus.ACTIVO);
            var previous = certificates.findByCompanyIdAndStatus(
                    company.getId(), ManagedCertificateStatus.ANTERIOR);
            previous.ifPresent(value -> removePrevious(value, now, userId));
            active.ifPresent(value -> {
                value.markPrevious(now, userId);
                certificates.saveAndFlush(value);
            });
            var imported = certificates.save(ManagedVerifactuCertificate.active(
                    certificateId, company.getId(), material.subject(), material.issuer(),
                    material.serialNumber(), material.taxId(), material.validFrom(),
                    material.validUntil(), material.fingerprint(), material.publicChainPkcs7(),
                    newSecret, now, userId));
            audit.record(
                    active.isPresent()
                            ? "VERIFACTU_CERTIFICATE_REPLACED"
                            : "VERIFACTU_CERTIFICATE_IMPORTED",
                    AuditResult.EXITO,
                    publicDetails(imported));
            return ManagedCertificateView.from(imported);
        } catch (RuntimeException exception) {
            if (newSecret != null) {
                secrets.delete(newSecret);
            }
            throw exception;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ManagedCertificateView> list() {
        var companyId = organization.currentCompany().getId();
        return java.util.stream.Stream.of(
                        certificates.findByCompanyIdAndStatus(
                                companyId, ManagedCertificateStatus.ACTIVO),
                        certificates.findByCompanyIdAndStatus(
                                companyId, ManagedCertificateStatus.ANTERIOR))
                .flatMap(java.util.Optional::stream)
                .map(ManagedCertificateView::from)
                .toList();
    }

    // Elimina la clave activa manteniendo sus metadatos publicos y auditoria.
    @Transactional
    public void deleteActive(String confirmation, Authentication authentication) {
        if (!"ELIMINAR CERTIFICADO".equals(confirmation)) {
            throw new IllegalArgumentException(
                    "La confirmacion para eliminar el certificado no es valida");
        }
        var companyId = organization.currentCompany().getId();
        var userId = organization.currentUser(authentication).getId();
        var active = certificates.findByCompanyIdAndStatus(
                        companyId, ManagedCertificateStatus.ACTIVO)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe un certificado VERI*FACTU activo"));
        var secret = active.getSecretPath();
        active.deleteActive(Instant.now(clock), userId);
        certificates.save(active);
        audit.record("VERIFACTU_CERTIFICATE_DELETED", AuditResult.EXITO,
                publicDetails(active));
        afterCommit(() -> secrets.delete(secret));
    }

    private void removePrevious(
            ManagedVerifactuCertificate previous, Instant changedAt, UUID userId) {
        var oldSecret = previous.getSecretPath();
        previous.removeSecret(changedAt, userId);
        certificates.saveAndFlush(previous);
        afterCommit(() -> secrets.delete(oldSecret));
    }

    private void registerRollbackCleanup(String secretPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    secrets.delete(secretPath);
                }
            }
        });
    }

    private static void afterCommit(Runnable operation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            operation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                operation.run();
            }
        });
    }

    private static Map<String, Object> publicDetails(
            ManagedVerifactuCertificate certificate) {
        return Map.of(
                "certificateId", certificate.getId(),
                "taxId", certificate.getTaxId(),
                "fingerprint", certificate.getFingerprint(),
                "validUntil", certificate.getValidUntil().toString());
    }
}
