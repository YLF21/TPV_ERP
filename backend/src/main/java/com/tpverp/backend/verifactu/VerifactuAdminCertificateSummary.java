package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuAdminCertificateSummary(
        boolean configured,
        boolean valid,
        String warningCode,
        Instant validUntil) {
}
