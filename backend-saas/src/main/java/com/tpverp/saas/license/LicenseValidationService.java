package com.tpverp.saas.license;

import java.time.Clock;
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
        authenticator.requireToken(installation, token);
        installation.validatedAt(clock.instant());
        SaasLicense license = installation.getLicense();
        VerifactuPolicySnapshot policy = verifactuPolicies.required(
                license.getCompany().getTaxpayerType());
        return new LicenseSaasValidationResponse(
                license.getStatus(),
                license.getValidUntil(),
                policy.activationDate(),
                policy.version(),
                policy.updatedAt());
    }
}
