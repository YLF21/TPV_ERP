package com.tpv.licenseissuer.crypto;

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
