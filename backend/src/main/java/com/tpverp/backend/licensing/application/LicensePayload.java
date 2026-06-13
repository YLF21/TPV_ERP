package com.tpverp.backend.licensing.application;

public record LicensePayload(
        String installationId,
        String installationReference,
        String company,
        String store,
        String validFrom,
        String validUntil,
        int maxWindows,
        int maxPda,
        String issuedAt) {
}
