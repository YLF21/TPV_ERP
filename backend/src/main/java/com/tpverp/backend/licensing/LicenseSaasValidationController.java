package com.tpverp.backend.licensing;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/license")
public class LicenseSaasValidationController {

    private final LicenseSaasValidationEndpointService service;

    public LicenseSaasValidationController(LicenseSaasValidationEndpointService service) {
        this.service = service;
    }

    @PostMapping("/validate")
    public LicenseSaasValidationResponse validate(
            @Valid @RequestBody LicenseSaasValidationRequest request) {
        return service.validate(request);
    }
}
