package com.tpverp.backend.licensing.application;

import java.time.Instant;

public record LicensePreview(
        String reference,
        String company,
        String store,
        Instant validFrom,
        Instant validUntil,
        int maxWindows,
        int maxPda,
        TaxRegime impuestos,
        String issuerKeyId,
        String fileHash) {
}
