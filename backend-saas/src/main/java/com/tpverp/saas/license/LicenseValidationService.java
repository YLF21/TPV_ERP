package com.tpverp.saas.license;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class LicenseValidationService {

    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final Clock clock;
    private final VerifactuActivationPolicyResolver verifactuPolicies;

    public LicenseValidationService(
            SaasInstallationRepository installations,
            InstallationAuthenticator authenticator,
            Clock clock,
            VerifactuActivationPolicyResolver verifactuPolicies) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.clock = clock;
        this.verifactuPolicies = verifactuPolicies;
    }

    @Transactional
    public LicenseSaasValidationResponse validate(LicenseSaasValidationRequest request, String token) {
        SaasInstallation installation = installations
                .findByInstallationIdAndLicense_Reference(request.installationId(), request.licenseReference())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Instalacion no vinculada"));
        Instant now = clock.instant();
        if (installation.isActive()) {
            authenticator.requireToken(installation, token);
            installation.validatedAt(now);
        } else {
            // A revoked installation must receive a signed, authoritative
            // blocking state. Returning 401 here would make the local ERP keep
            // its last valid snapshot until the offline grace period expires.
            authenticator.requireKnownToken(installation, token);
        }
        SaasLicense license = installation.getLicense();
        LicenseSaasStatus effectiveStatus = !installation.isActive()
                ? LicenseSaasStatus.BLOQUEADA_MANUAL
                : license.getStatus() == LicenseSaasStatus.VALIDA
                && !now.isBefore(license.getValidUntil())
                ? LicenseSaasStatus.CADUCADA
                : license.getStatus();
        VerifactuPolicySnapshot policy = verifactuPolicies.required(
                license.getCompany().getTaxpayerType());
        return new LicenseSaasValidationResponse(
                effectiveStatus,
                license.getValidUntil(),
                policy.activationDate(),
                policy.version(),
                policy.updatedAt(),
                license.getCompany().getCommercialProfile(),
                license.getMaxWindows(),
                license.getMaxPda(),
                license.getLicenseVersion(),
                license.getCompany().getId(),
                installation.getStore().getId(),
                license.getReference(),
                license.getCompany().getTaxId());
    }
}
