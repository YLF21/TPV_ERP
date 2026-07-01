package com.tpverp.saas.admin;

import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateCompanyRequest(
        @NotBlank String name,
        @NotBlank String taxId,
        @NotNull TaxpayerType taxpayerType,
        @NotNull TaxRegime impuestos,
        @NotBlank String storeCode,
        String storeName,
        @NotNull Instant validUntil,
        int maxWindows,
        int maxPda) {
}
