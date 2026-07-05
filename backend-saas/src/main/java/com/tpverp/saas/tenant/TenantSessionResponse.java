package com.tpverp.saas.tenant;

import java.util.UUID;

public record TenantSessionResponse(String username, UUID companyId, String companyName, String roleName) {
}
