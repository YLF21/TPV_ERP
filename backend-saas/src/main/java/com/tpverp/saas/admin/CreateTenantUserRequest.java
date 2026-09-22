package com.tpverp.saas.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import com.tpverp.saas.access.TenantCompanyPrivilege;
import java.util.Set;
import java.util.UUID;

public record CreateTenantUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(min = 4, max = 120) String password,
        @NotBlank @Size(max = 40) String roleName,
        @Size(max = 2000) Set<@NotNull UUID> storeIds,
        Set<@NotNull TenantCompanyPrivilege> companyPrivileges) {
    public CreateTenantUserRequest(String username, String password, String roleName) {
        this(username, password, roleName, Set.of(), Set.of());
    }
}
