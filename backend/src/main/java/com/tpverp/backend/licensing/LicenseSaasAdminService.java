package com.tpverp.backend.licensing;

import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

public class LicenseSaasAdminService {

    private final LicenseRepository licenses;
    private final Clock clock;

    public LicenseSaasAdminService(LicenseRepository licenses, Clock clock) {
        this.licenses = licenses;
        this.clock = clock;
    }

    @Transactional
    public LicenseSaasValidationResponse block(String reference) {
        License license = license(reference);
        license.markSaasBlocked(Instant.now(clock));
        licenses.save(license);
        return response(license);
    }

    @Transactional
    public LicenseSaasValidationResponse unblock(String reference) {
        License license = license(reference);
        license.markSaasValidated(Instant.now(clock), license.getValidaHasta());
        licenses.save(license);
        return response(license);
    }

    private License license(String reference) {
        return licenses.findByReferencia(reference)
                .orElseThrow(() -> new IllegalArgumentException("Licencia no encontrada"));
    }

    private static LicenseSaasValidationResponse response(License license) {
        return new LicenseSaasValidationResponse(
                license.getEstadoSaas(),
                license.getValidaHasta(),
                license.getVerifactuActivationDate(),
                license.getVerifactuPolicyVersion() == null ? 0 : license.getVerifactuPolicyVersion(),
                license.getVerifactuPolicyUpdatedAt());
    }
}
