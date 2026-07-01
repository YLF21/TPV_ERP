package com.tpverp.backend.licensing;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;

public class LicenseOfflineWarningScheduler {

    static final String WARNING_MESSAGE = "FALTA CONEXION Y LICENCIA CADUCADA";

    private final LicenseRepository licenses;
    private final AuditService audit;
    private final Clock clock;

    public LicenseOfflineWarningScheduler(
            LicenseRepository licenses,
            AuditService audit,
            Clock clock) {
        this.licenses = licenses;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tpv.license.warning-interval-ms:600000}")
    public void tick() {
        licenses.findAll().stream()
                .filter(License::isActiva)
                .filter(this::requiresWarning)
                .findFirst()
                .ifPresent(this::recordWarning);
    }

    private boolean requiresWarning(License license) {
        return license.requiresOfflineExpiredWarningAt(Instant.now(clock));
    }

    private void recordWarning(License license) {
        audit.record(
                "LICENSE_OFFLINE_EXPIRED_WARNING",
                AuditResult.FALLO,
                Map.of(
                        "mensaje", WARNING_MESSAGE,
                        "licenseReference", license.getReferencia(),
                        "validUntil", license.getValidaHasta().toString(),
                        "lastSaasValidationAt", license.getUltimaValidacionSaas().toString()));
    }
}
