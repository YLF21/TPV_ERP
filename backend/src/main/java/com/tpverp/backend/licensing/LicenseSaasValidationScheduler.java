package com.tpverp.backend.licensing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConditionalOnProperty("tpv.license.saas-url")
public class LicenseSaasValidationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LicenseSaasValidationScheduler.class);

    private final LicenseSaasValidationService service;

    public LicenseSaasValidationScheduler(LicenseSaasValidationService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${tpv.license.validation-initial-delay-ms:60000}",
            fixedDelayString = "${tpv.license.validation-interval-ms:600000}")
    public void tick() {
        java.util.List<java.util.UUID> licenseIds;
        try {
            licenseIds = service.activeSaasLicenseIds();
        } catch (RuntimeException exception) {
            LOG.warn("No se pudieron enumerar las licencias SaaS; se conserva el ultimo estado persistido: {}",
                    exception.getMessage());
            return;
        }
        for (var licenseId : licenseIds) {
            try {
                service.validateLicense(licenseId);
            } catch (RuntimeException exception) {
                LOG.warn("No se pudo refrescar la licencia SaaS {}; se conserva su ultimo estado persistido: {}",
                        licenseId, exception.getMessage());
            }
        }
    }
}
