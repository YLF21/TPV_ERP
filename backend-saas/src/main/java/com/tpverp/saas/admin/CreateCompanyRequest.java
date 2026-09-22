package com.tpverp.saas.admin;

import com.tpverp.saas.license.TaxpayerType;
import com.tpverp.saas.license.CommercialProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record CreateCompanyRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank String taxId,
        @NotNull TaxpayerType taxpayerType,
        CommercialProfile commercialProfile,
        @NotNull Map<String, String> companyAddress,
        @Size(max = 160) String contactName,
        @Email @Size(max = 160) String contactEmail,
        @Size(max = 40) String contactPhone,
        @Pattern(regexp = "NORMAL|ATENCION|BLOQUEADO") String supportStatus,
        @Size(max = 4000) String notes,
        @NotEmpty List<@NotNull @Valid CompanyOwner> owners) {
    public CreateCompanyRequest(String name, String taxId, TaxpayerType taxpayerType,
            CommercialProfile commercialProfile, Map<String, String> companyAddress, List<CompanyOwner> owners) {
        this(name, taxId, taxpayerType, commercialProfile, companyAddress,
                null, null, null, null, null, owners);
    }
}
