package com.tpverp.saas.admin;

import com.tpverp.saas.license.TaxpayerType;
import java.time.Instant;
import java.time.LocalDate;

public record VerifactuActivationPolicyResponse(
        TaxpayerType taxpayerType,
        LocalDate activationDate,
        long version,
        Instant updatedAt,
        String updatedBy,
        String reason,
        long activeLicenses,
        long linkedInstallations) {
}
