package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalRequiredSubmissionService {
    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final FiscalRequiredSubmissionRepository submissions;

    public FiscalRequiredSubmissionService(CurrentOrganization organization,
            InstallationRepository installations, FiscalRequiredSubmissionRepository submissions) {
        this.organization = organization;
        this.installations = installations;
        this.submissions = submissions;
    }

    @Transactional
    public FiscalRequiredSubmissionView register(String reference) {
        var normalized = reference == null ? "" : reference.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("La referencia del requerimiento es obligatoria");
        }
        var company = organization.currentCompany();
        var installation = installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var existing = submissions.findByCompanyIdAndInstallationIdAndReference(
                company.getId(), installation.getId(), normalized).orElse(null);
        var saved = existing == null ? submissions.save(new FiscalRequiredSubmission(
                company.getId(), installation.getId(), normalized, Instant.now())) : existing;
        return new FiscalRequiredSubmissionView(saved.getId(), saved.getReference(),
                saved.getStatus(), saved.getRequestedAt());
    }
}
