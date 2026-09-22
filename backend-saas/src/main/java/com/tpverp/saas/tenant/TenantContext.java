package com.tpverp.saas.tenant;

import java.util.UUID;
import java.util.Set;
import com.tpverp.saas.access.TenantCompanyPrivilege;

public record TenantContext(UUID companyId, String username, String roleName,
        Set<TenantCompanyPrivilege> companyPrivileges, Set<UUID> storeIds) {
    public TenantContext {
        companyPrivileges = Set.copyOf(companyPrivileges);
        storeIds = Set.copyOf(storeIds);
    }
    public boolean permits(TenantCompanyPrivilege privilege) { return companyPrivileges.contains(privilege); }
}
