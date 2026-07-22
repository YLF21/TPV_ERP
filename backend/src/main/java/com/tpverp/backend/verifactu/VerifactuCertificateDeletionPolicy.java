package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuCertificateDeletionPolicy {

    public static final String FIRST_SUBMISSION_RECORDED = "FIRST_SUBMISSION_RECORDED";
    public static final String VERIFACTU_ACTIVE = "VERIFACTU_ACTIVE";
    public static final String NON_FINAL_SUBMISSIONS_EXIST = "NON_FINAL_SUBMISSIONS_EXIST";
    public static final String LICENSE_NOT_AVAILABLE = "LICENSE_NOT_AVAILABLE";
    public static final String NOT_ACTIVE_CERTIFICATE = "NOT_ACTIVE_CERTIFICATE";

    private static final EnumSet<FiscalSubmissionStatus> NON_FINAL_STATUSES = EnumSet.of(
            FiscalSubmissionStatus.PENDIENTE,
            FiscalSubmissionStatus.ENVIANDO,
            FiscalSubmissionStatus.ENVIADO);

    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuConfigurationRepository configurations;
    private final VerifactuActivationService activation;
    private final FiscalSubmissionStateRepository submissionStates;
    private final Clock clock;

    public VerifactuCertificateDeletionPolicy(
            CurrentOrganization organization,
            InstallationRepository installations,
            LicenseRepository licenses,
            VerifactuConfigurationRepository configurations,
            VerifactuActivationService activation,
            FiscalSubmissionStateRepository submissionStates,
            Clock clock) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.configurations = configurations;
        this.activation = activation;
        this.submissionStates = submissionStates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VerifactuCertificateDeletionDecision evaluate() {
        return evaluate(false);
    }

    @Transactional
    public VerifactuCertificateDeletionDecision evaluateForUpdate() {
        return evaluate(true);
    }

    private VerifactuCertificateDeletionDecision evaluate(boolean lockConfiguration) {
        var store = organization.currentStore();
        var companyId = store.getEmpresa().getId();
        if (lockConfiguration) {
            configurations.insertIfMissing(java.util.UUID.randomUUID(), companyId);
        }
        var configuration = (lockConfiguration
                ? configurations.findForUpdateByCompanyId(companyId)
                : configurations.findByCompanyId(companyId))
                .orElseGet(() -> new VerifactuConfiguration(companyId));
        if (configuration.getFirstSubmissionAt() != null) {
            return VerifactuCertificateDeletionDecision.blocked(FIRST_SUBMISSION_RECORDED);
        }

        var installation = installations.findAll().stream().findFirst().orElse(null);
        if (installation == null) {
            return VerifactuCertificateDeletionDecision.blocked(LICENSE_NOT_AVAILABLE);
        }
        var license = licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                        store.getId(), installation.getId())
                .orElse(null);
        if (license == null) {
            return VerifactuCertificateDeletionDecision.blocked(LICENSE_NOT_AVAILABLE);
        }

        Instant now = Instant.now(clock);
        if (activation.isActive(
                configuration,
                license.getTaxpayerType(),
                license.getVerifactuActivationDate(),
                now,
                ZoneId.of(store.getTimezone()))) {
            return VerifactuCertificateDeletionDecision.blocked(VERIFACTU_ACTIVE);
        }
        if (submissionStates.countByCompanyIdAndStatusIn(companyId, NON_FINAL_STATUSES) > 0) {
            return VerifactuCertificateDeletionDecision.blocked(NON_FINAL_SUBMISSIONS_EXIST);
        }
        return VerifactuCertificateDeletionDecision.allowed();
    }
}
