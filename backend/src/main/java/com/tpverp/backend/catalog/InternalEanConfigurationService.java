package com.tpverp.backend.catalog;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalEanConfigurationService {

    private final InternalEanConfigurationRepository configurations;
    private final CurrentOrganization organization;
    private final AuditService audit;
    private final Clock clock;

    public InternalEanConfigurationService(
            InternalEanConfigurationRepository configurations,
            CurrentOrganization organization,
            AuditService audit,
            Clock clock) {
        this.configurations = configurations;
        this.organization = organization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View current() {
        var companyId = organization.currentCompany().getId();
        return configurations.findById(companyId)
                .map(configuration -> new View(
                        companyId,
                        configuration.getCompanyCode(),
                        configuration.getConfigVersion(),
                        true))
                .orElseGet(() -> new View(companyId, null, 0L, false));
    }

    @Transactional(readOnly = true)
    public InternalEanConfiguration requireCurrent() {
        var companyId = organization.currentCompany().getId();
        return configurations.findById(companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "internal_ean_configuration_required"));
    }

    @Transactional
    public View update(long expectedVersion, String companyCode) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("internal_ean_configuration_version_invalid");
        }
        var companyId = organization.currentCompany().getId();
        var existing = configurations.findForUpdate(companyId);
        var currentVersion = existing
                .map(InternalEanConfiguration::getConfigVersion)
                .orElse(0L);
        if (currentVersion != expectedVersion) {
            throw new IllegalStateException(
                    "internal_ean_configuration_version_conflict");
        }
        var now = clock.instant();
        var configuration = existing.orElseGet(() ->
                new InternalEanConfiguration(companyId, companyCode, now));
        if (existing.isPresent()) {
            configuration.update(companyCode, now);
        }
        var saved = configurations.saveAndFlush(configuration);
        var details = new LinkedHashMap<String, Object>();
        details.put("companyId", companyId.toString());
        details.put("companyCode", saved.getCompanyCode());
        details.put("version", saved.getConfigVersion());
        audit.record("INTERNAL_EAN_CONFIGURATION_SET", AuditResult.EXITO, details);
        return new View(
                companyId,
                saved.getCompanyCode(),
                saved.getConfigVersion(),
                true);
    }

    public record View(
            java.util.UUID companyId,
            String companyCode,
            long version,
            boolean configured) {
    }
}
