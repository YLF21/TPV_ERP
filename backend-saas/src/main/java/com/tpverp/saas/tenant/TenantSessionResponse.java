package com.tpverp.saas.tenant;

import java.util.UUID;
import java.util.Set;
import com.tpverp.saas.access.TenantCompanyPrivilege;

public record TenantSessionResponse(String username, UUID companyId, String companyName, String roleName,
        Set<TenantCompanyPrivilege> companyPrivileges) {
}
