package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalRequiredSubmissionService {
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final FiscalRequiredSubmissionRepository submissions;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalExportService exports;

    public FiscalRequiredSubmissionService(CurrentOrganization organization,
            InstallationRepository installations, FiscalRequiredSubmissionRepository submissions,
            VerifactuConfigurationRepository configurations, FiscalExportService exports) {
        this.organization = organization;
        this.installations = installations;
        this.submissions = submissions;
        this.configurations = configurations;
        this.exports = exports;
    }

    @Transactional
    public FiscalRequiredSubmissionView register(String reference) {
        var normalized = reference == null ? "" : reference.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("La referencia del requerimiento es obligatoria");
        }
        if (normalized.length() > 18) {
            throw new IllegalArgumentException(
                    "La referencia del requerimiento no puede superar 18 caracteres");
        }
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        requireNoVerifactu(company.getId());
        var existing = submissions.findByCompanyIdAndInstallationIdAndReference(
                company.getId(), installation.getId(), normalized).orElse(null);
        var saved = existing == null ? submissions.save(new FiscalRequiredSubmission(
                company.getId(), installation.getId(), normalized, Instant.now())) : existing;
        return view(saved);
    }

    @Transactional
    public FiscalRequiredSubmissionExportView export(UUID id, FiscalExportKind kind,
            OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        if (kind == null || periodStart == null || periodEnd == null
                || periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException(
                    "El requerimiento exige tipo y un periodo completo en orden");
        }
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        requireNoVerifactu(company.getId());
        var submission = submissions.findByIdAndCompanyIdAndInstallationId(
                id, company.getId(), installation.getId())
                .orElseThrow(() -> new IllegalArgumentException("Requerimiento fiscal no encontrado"));
        if (!"PENDIENTE".equals(submission.getStatus())) {
            throw new IllegalStateException("El requerimiento fiscal ya esta cerrado");
        }
        var exported = exports.export(kind, periodStart, periodEnd);
        submission.markExported(exported.exportId(), Instant.now());
        var saved = submissions.save(submission);
        return new FiscalRequiredSubmissionExportView(view(saved), exported);
    }

    private void requireNoVerifactu(UUID companyId) {
        var mode = configurations.findByCompanyId(companyId)
                .map(VerifactuConfiguration::getCurrentMode)
                .orElse(FiscalMode.PRE_SIF);
        if (mode != FiscalMode.NO_VERIFACTU) {
            throw new IllegalStateException(
                    "Los requerimientos fiscales solo estan disponibles en NO VERI*FACTU");
        }
    }

    private static FiscalRequiredSubmissionView view(FiscalRequiredSubmission submission) {
        return new FiscalRequiredSubmissionView(submission.getId(), submission.getReference(),
                submission.getStatus(), submission.getRequestedAt(), submission.getAttendedAt(),
                submission.getExportId());
    }
}
