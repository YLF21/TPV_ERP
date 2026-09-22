package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record UpdateCompanyProfileRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull Map<String, String> companyAddress,
        @Size(max = 160) String contactName,
        @Email @Size(max = 160) String contactEmail,
        @Size(max = 40) String contactPhone,
        @NotBlank @Pattern(regexp = "NORMAL|ATENCION|BLOQUEADO") String supportStatus,
        @Size(max = 4000) String notes,
        @NotEmpty List<@NotNull @Valid CompanyOwner> owners) {
}
