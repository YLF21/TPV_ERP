package com.tpverp.saas.admin;

import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.CommercialProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record EditCompanyDataRequest(
        @NotBlank String name,
        @NotNull TaxpayerType taxpayerType,
        CommercialProfile commercialProfile,
        @NotNull Map<String, String> companyAddress) {
}
