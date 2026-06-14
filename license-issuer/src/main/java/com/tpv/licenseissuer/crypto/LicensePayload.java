package com.tpv.licenseissuer.crypto;

import com.tpv.licenseissuer.model.TaxRegime;
import com.tpv.licenseissuer.model.TaxpayerType;

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
