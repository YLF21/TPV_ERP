package com.tpverp.saas.license;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/license")
public class LicenseController {

    private final LicenseLinkService linkService;
    private final LicenseValidationService validationService;

    public LicenseController(LicenseLinkService linkService, LicenseValidationService validationService) {
        this.linkService = linkService;
        this.validationService = validationService;
    }

    @PostMapping("/link")
    public LicenseSaasLinkResponse link(@Valid @RequestBody LicenseSaasLinkRequest request) {
        return linkService.link(request);
    }

    @PostMapping("/validate")
    public LicenseSaasValidationResponse validate(
            @Valid @RequestBody LicenseSaasValidationRequest request,
            @RequestHeader(name = "X-TPV-Installation-Token", required = false) String token) {
        return validationService.validate(request, token);
    }
}
