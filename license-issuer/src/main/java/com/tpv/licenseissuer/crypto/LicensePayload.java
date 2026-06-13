package com.tpv.licenseissuer.crypto;

import com.tpv.licenseissuer.model.TaxRegime;

public record LicensePayload(
        String installationId,
        String installationReference,
        String company,
        String store,
        String validFrom,
        String validUntil,
        int maxWindows,
        int maxPda,
        TaxRegime impuestos,
        String issuedAt) {
}
