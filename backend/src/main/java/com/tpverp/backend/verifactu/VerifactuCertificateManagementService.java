package com.tpverp.backend.verifactu;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.organization.CompanyRepository;
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
    private final VerifactuSecretDeletionService secretDeletions;
    private final CurrentOrganization organization;
    private final CompanyRepository companies;
    private final VerifactuCertificateDeletionPolicy deletionPolicy;
    private final AuditService audit;
    private final Clock clock;

    public VerifactuCertificateManagementService(
            ManagedVerifactuCertificateRepository certificates,
            VerifactuCertificateImporter importer,
            VerifactuCertificateSecretStore secrets,
            VerifactuSecretDeletionService secretDeletions,
            CurrentOrganization organization,
            CompanyRepository companies,
            VerifactuCertificateDeletionPolicy deletionPolicy,
            AuditService audit,
            Clock clock) {
        this.certificates = certificates;
        this.importer = importer;
        this.secrets = secrets;
        this.secretDeletions = secretDeletions;
        this.organization = organization;
        this.companies = companies;
        this.deletionPolicy = deletionPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    // Importa o sustituye el certificado conservando solo una clave anterior.
    @Transactional
    public ManagedCertificateView importCertificate(
            byte[] pkcs12,
            char[] password,
            UUID expectedActiveCertificateId,
            String confirmation,
            Authentication authentication) {
        var company = organization.currentCompany();
        var userId = organization.currentUser(authentication).getId();
        var certificateId = UUID.randomUUID();
        String newSecret = null;
        ImportedCertificateMaterial material = null;
        try {
            try {
                material = importer.importPkcs12(pkcs12, password, company.getTaxId());
            } catch (VerifactuCertificateImportException exception) {
                throw VerifactuCertificateApiException.badRequest(
                        "CERTIFICATE_VALIDATION_FAILED",
                        "message.verifactu.certificate.validation_failed",
                        Map.of("errors", exception.failures().stream()
                                .map(failure -> Map.of("code", failure.apiCode()))
                                .toList()));
            }
            companies.findForUpdate(company.getId())
                    .orElseThrow(() -> new IllegalStateException("La empresa no existe"));
            var active = certificates.findByCompanyIdAndStatus(
                    company.getId(), ManagedCertificateStatus.ACTIVO);
            validateReplacement(active.orElse(null), expectedActiveCertificateId, confirmation);

            byte[] privateKey = material.privateKeyPkcs8();
            try {
                try {
                    newSecret = secrets.write(company.getId(), certificateId, privateKey);
                } catch (RuntimeException exception) {
                    throw VerifactuCertificateApiException.internalServerError(
                            "CERTIFICATE_STORAGE_FAILED",
                            "message.verifactu.certificate.storage_failed");
                }
            } finally {
                Arrays.fill(privateKey, (byte) 0);
            }
            registerRollbackCleanup(company.getId(), newSecret);
            var now = Instant.now(clock);
            var previous = certificates.findByCompanyIdAndStatus(
                    company.getId(), ManagedCertificateStatus.ANTERIOR);
            var replacedDetails = active.map(VerifactuCertificateManagementService::publicDetails)
                    .orElse(null);
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
                    active.isPresent()
                            ? Map.of("previous", replacedDetails, "current", publicDetails(imported))
                            : Map.of("current", publicDetails(imported)));
            return view(imported, deletionPolicy.evaluate());
        } catch (RuntimeException exception) {
            if (newSecret != null
                    && !TransactionSynchronizationManager.isSynchronizationActive()) {
                secretDeletions.enqueueAfterRollback(
                        company.getId(), newSecret,
                        VerifactuSecretDeletionReason.IMPORT_ROLLBACK);
            }
            throw exception;
        } finally {
            if (material != null) {
                material.clearPrivateKey();
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ManagedCertificateView> list() {
        var companyId = organization.currentCompany().getId();
        var active = certificates.findByCompanyIdAndStatus(
                companyId, ManagedCertificateStatus.ACTIVO);
        var previous = certificates.findByCompanyIdAndStatus(
                companyId, ManagedCertificateStatus.ANTERIOR);
        var activeDeletion = deletionPolicy.evaluate();
        var previousDeletion = VerifactuCertificateDeletionDecision.blocked(
                VerifactuCertificateDeletionPolicy.NOT_ACTIVE_CERTIFICATE);
        return java.util.stream.Stream.concat(
                        active.stream().map(value -> view(value, activeDeletion)),
                        previous.stream().map(value -> view(value, previousDeletion)))
                .toList();
    }

    // Elimina la clave activa manteniendo sus metadatos publicos y auditoria.
    @Transactional
    public void deleteActive(String confirmation, Authentication authentication) {
        if (!"ELIMINAR CERTIFICADO".equals(confirmation)) {
            throw VerifactuCertificateApiException.badRequest(
                    "VERIFACTU_CERTIFICATE_DELETE_CONFIRMATION_INVALID",
                    "message.verifactu.certificate.delete_confirmation_invalid");
        }
        var company = organization.currentCompany();
        var companyId = company.getId();
        var userId = organization.currentUser(authentication).getId();
        companies.findForUpdate(companyId)
                .orElseThrow(() -> new IllegalStateException("La empresa no existe"));
        var active = certificates.findByCompanyIdAndStatus(
                        companyId, ManagedCertificateStatus.ACTIVO)
                .orElseThrow(() -> VerifactuCertificateApiException.conflict(
                        "VERIFACTU_CERTIFICATE_NOT_CONFIGURED",
                        "message.verifactu.certificate.not_configured"));
        var deletion = deletionPolicy.evaluateForUpdate();
        if (!deletion.canDelete()) {
            throw VerifactuCertificateApiException.conflict(
                    "VERIFACTU_CERTIFICATE_DELETE_BLOCKED",
                    "message.verifactu.certificate.delete_blocked",
                    Map.of("deleteBlockReason", deletion.deleteBlockReason()));
        }
        var previousDetails = publicDetails(active);
        var secret = active.getSecretPath();
        secretDeletions.enqueue(
                companyId, active.getId(), secret,
                VerifactuSecretDeletionReason.ACTIVE_DELETED);
        active.deleteActive(Instant.now(clock), userId);
        certificates.save(active);
        audit.record("VERIFACTU_CERTIFICATE_DELETED", AuditResult.EXITO,
                Map.of("previous", previousDetails, "current", publicDetails(active)));
    }

    private void validateReplacement(
            ManagedVerifactuCertificate active,
            UUID expectedActiveCertificateId,
            String confirmation) {
        if (active == null) {
            if (expectedActiveCertificateId != null) {
                throw VerifactuCertificateApiException.conflict(
                        "VERIFACTU_ACTIVE_CERTIFICATE_CHANGED",
                        "message.verifactu.certificate.active_changed");
            }
            return;
        }
        if (!"SUSTITUIR CERTIFICADO".equals(confirmation)) {
            throw VerifactuCertificateApiException.badRequest(
                    "VERIFACTU_CERTIFICATE_REPLACEMENT_CONFIRMATION_REQUIRED",
                    "message.verifactu.certificate.replacement_confirmation_required");
        }
        if (!active.getId().equals(expectedActiveCertificateId)) {
            throw VerifactuCertificateApiException.conflict(
                    "VERIFACTU_ACTIVE_CERTIFICATE_CHANGED",
                    "message.verifactu.certificate.active_changed");
        }
    }

    private ManagedCertificateView view(
            ManagedVerifactuCertificate certificate,
            VerifactuCertificateDeletionDecision deletion) {
        return ManagedCertificateView.from(
                certificate, Instant.now(clock), deletion);
    }

    private void removePrevious(
            ManagedVerifactuCertificate previous, Instant changedAt, UUID userId) {
        var oldSecret = previous.getSecretPath();
        secretDeletions.enqueue(
                previous.getCompanyId(), previous.getId(), oldSecret,
                VerifactuSecretDeletionReason.PREVIOUS_REPLACED);
        previous.removeSecret(changedAt, userId);
        certificates.saveAndFlush(previous);
    }

    private void registerRollbackCleanup(UUID companyId, String secretPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    secretDeletions.enqueueAfterRollback(
                            companyId, secretPath,
                            VerifactuSecretDeletionReason.IMPORT_ROLLBACK);
                }
            }
        });
    }

    private static Map<String, Object> publicDetails(
            ManagedVerifactuCertificate certificate) {
        return Map.of(
                "certificateId", certificate.getId(),
                "status", certificate.getStatus().name(),
                "taxId", certificate.getTaxId(),
                "fingerprint", certificate.getFingerprint(),
                "validUntil", certificate.getValidUntil().toString());
    }
}
