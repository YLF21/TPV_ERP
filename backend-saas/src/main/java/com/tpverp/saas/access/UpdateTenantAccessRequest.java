package com.tpverp.saas.access;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record UpdateTenantAccessRequest(
        @NotBlank @Size(max = 40) String roleName,
        @NotNull Set<@NotNull TenantCompanyPrivilege> companyPrivileges,
        @NotNull @Size(max = 2000) Set<@NotNull UUID> storeIds) { }
