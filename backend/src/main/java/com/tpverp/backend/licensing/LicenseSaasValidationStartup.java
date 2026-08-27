package com.tpverp.backend.licensing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConditionalOnProperty("tpv.license.saas-url")
public class LicenseSaasValidationStartup {
    private static final Logger LOG = LoggerFactory.getLogger(LicenseSaasValidationStartup.class);

    private final LicenseSaasValidationService service;

    public LicenseSaasValidationStartup(LicenseSaasValidationService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        java.util.List<java.util.UUID> licenseIds;
        try {
            licenseIds = service.activeSaasLicenseIds();
        } catch (RuntimeException exception) {
            LOG.warn("No se pudieron enumerar las licencias SaaS al arrancar; se conserva el ultimo estado: {}",
                    exception.getMessage());
            return;
        }
        for (var licenseId : licenseIds) {
            try {
                service.validateLicense(licenseId);
            } catch (RuntimeException exception) {
                LOG.warn("SaaS no disponible para la licencia {} al arrancar; se conserva su ultimo estado: {}",
                        licenseId, exception.getMessage());
            }
        }
    }
}
