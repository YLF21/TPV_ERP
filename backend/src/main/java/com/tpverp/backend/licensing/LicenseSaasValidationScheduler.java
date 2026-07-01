package com.tpverp.backend.licensing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ConditionalOnProperty("tpv.license.saas-url")
public class LicenseSaasValidationScheduler {

    private final LicenseSaasValidationService service;

    public LicenseSaasValidationScheduler(LicenseSaasValidationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${tpv.license.validation-interval-ms:600000}")
    public void tick() {
        service.validateActiveLicense();
    }
}
