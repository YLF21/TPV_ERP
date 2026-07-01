package com.tpverp.backend.licensing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@ConditionalOnProperty("tpv.license.saas-url")
public class LicenseSaasValidationStartup {

    private final LicenseSaasValidationService service;

    public LicenseSaasValidationStartup(LicenseSaasValidationService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        service.validateActiveLicense();
    }
}
