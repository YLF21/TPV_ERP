package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalRequiredSubmissionService {
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final FiscalRequiredSubmissionRepository submissions;
    private final VerifactuConfigurationRepository configurations;
    private final FiscalExportService exports;
    private final FiscalRecordRepository records;

    @Autowired
    public FiscalRequiredSubmissionService(CurrentOrganization organization,
            InstallationRepository installations, LicenseRepository licenses,
            FiscalRequiredSubmissionRepository submissions,
            VerifactuConfigurationRepository configurations, FiscalExportService exports,
            FiscalRecordRepository records) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.submissions = submissions;
        this.configurations = configurations;
        this.exports = exports;
        this.records = records;
    }

    /** Compatibility constructor for focused unit tests and adapters. */
    public FiscalRequiredSubmissionService(CurrentOrganization organization,
            InstallationRepository installations, FiscalRequiredSubmissionRepository submissions,
            VerifactuConfigurationRepository configurations, FiscalExportService exports) {
        this(organization, installations, null, submissions, configurations, exports, null);
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
        var installation = resolveInstallation(company.getId());
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
        if (kind != FiscalExportKind.BILLING) {
            throw new IllegalArgumentException(
                    "Los requerimientos fiscales solo admiten exportaciones BILLING");
        }
        if (periodStart == null || periodEnd == null
                || periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException(
                    "El requerimiento exige tipo y un periodo completo en orden");
        }
        var company = organization.currentCompany();
        var installation = resolveInstallation(company.getId());
        requireNoVerifactu(company.getId());
        var submission = submissions.findForUpdateByIdAndCompanyIdAndInstallationId(
                id, company.getId(), installation.getId())
                .orElseThrow(() -> new IllegalArgumentException("Requerimiento fiscal no encontrado"));
        if (!"PENDIENTE".equals(submission.getStatus())) {
            throw new IllegalStateException("El requerimiento fiscal ya esta cerrado");
        }
        submission.freezePeriod(periodStart, periodEnd);
        if (records != null && records.countByCompanyIdAndStoreIdAndInstallationId(
                company.getId(), organization.currentStore().getId(), installation.getId()) > 1000) {
            throw new IllegalArgumentException("fiscal_export_use_export_jobs");
        }
        var exported = exports.export(kind, periodStart, periodEnd, submission.getReference());
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

    private com.tpverp.backend.installation.Installation resolveInstallation(UUID companyId) {
        return licenses == null
                ? FiscalInstallationResolver.resolveForCompany(companyId, installations, null)
                : FiscalInstallationResolver.resolveCurrent(organization, installations, licenses);
    }

    private static FiscalRequiredSubmissionView view(FiscalRequiredSubmission submission) {
        return new FiscalRequiredSubmissionView(submission.getId(), submission.getReference(),
                submission.getStatus(), submission.getRequestedAt(), submission.getAttendedAt(),
                submission.getExportId(), submission.getPeriodStart(), submission.getPeriodEnd());
    }
}
