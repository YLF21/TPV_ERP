package com.tpverp.saas.admin;

import com.tpverp.saas.license.TaxRegime;
import com.tpverp.saas.license.TaxpayerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EditCompanyDataRequest(
        @NotBlank String name,
        @NotNull TaxpayerType taxpayerType,
        @NotNull TaxRegime impuestos) {
}
