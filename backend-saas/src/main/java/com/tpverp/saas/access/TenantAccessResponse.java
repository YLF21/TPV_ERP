package com.tpverp.saas.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record TenantAccessResponse(String username, List<CompanyAccess> companies) {
    public record CompanyAccess(UUID companyId, String companyName, String roleName,
            Set<TenantCompanyPrivilege> companyPrivileges, List<StoreAccess> stores) { }
    public record StoreAccess(UUID storeId, String code, String name, String internalCode, boolean active) { }
}
