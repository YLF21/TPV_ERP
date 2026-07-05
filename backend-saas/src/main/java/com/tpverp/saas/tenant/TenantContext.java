package com.tpverp.saas.tenant;

import java.util.UUID;

public record TenantContext(UUID companyId, String username, String roleName) {
}
