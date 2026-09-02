package com.tpverp.saas.license;

import com.tpverp.saas.admin.LoginAttemptLimiter;
import com.tpverp.saas.admin.SaasAuthenticationController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/license")
public class LicenseController {

    private final LicenseLinkService linkService;
    private final LicenseValidationService validationService;
    private final LoginAttemptLimiter attempts;

    public LicenseController(
            LicenseLinkService linkService,
            LicenseValidationService validationService,
            LoginAttemptLimiter attempts) {
        this.linkService = linkService;
        this.validationService = validationService;
        this.attempts = attempts;
    }

    @PostMapping("/link")
    public LicenseSaasLinkResponse link(
            @Valid @RequestBody LicenseSaasLinkRequest request,
            @RequestHeader(name = "X-TPV-Installation-Token", required = false) String previousToken,
            @RequestHeader(name = "X-TPV-Link-Recovery-Token", required = false) String recoveryToken,
            HttpServletRequest httpRequest) {
        String pairingCode = pairingAttemptKey(request.pairingCode());
        String remoteAddress = SaasAuthenticationController.remoteAddress(httpRequest);
        if (attempts.blocked("license-link-code", pairingCode, remoteAddress)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos de enlace; vuelva a intentarlo mas tarde");
        }
        try {
            LicenseSaasLinkResponse response = linkService.link(request, previousToken, recoveryToken);
            attempts.success("license-link-code", pairingCode, remoteAddress);
            return response;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().is4xxClientError()
                    && exception.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) {
                attempts.failure("license-link-code", pairingCode, remoteAddress);
            }
            throw exception;
        }
    }

    @PostMapping("/validate")
    public LicenseSaasValidationResponse validate(
            @Valid @RequestBody LicenseSaasValidationRequest request,
            @RequestHeader(name = "X-TPV-Installation-Token", required = false) String token) {
        return validationService.validate(request, token);
    }

    private String pairingAttemptKey(String pairingCode) {
        try {
            return LicenseProvisioningData.requiredCode(pairingCode, "pairingCode");
        } catch (IllegalArgumentException exception) {
            return "INVALID-PAIRING-CODE";
        }
    }
}
