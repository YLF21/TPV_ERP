package com.tpverp.backend.licensing.application;

public record LicensePayload(
        String installationId,
        String installationReference,
        String taxId,
        TaxpayerType taxpayerType,
        String company,
        String store,
        String validFrom,
        String validUntil,
        int maxWindows,
        int maxPda,
        TaxRegime impuestos,
        String issuedAt) {
}
