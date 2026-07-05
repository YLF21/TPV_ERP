package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.UUID;

public record TenantUserResponse(
        UUID id,
        UUID companyId,
        String username,
        String roleName,
        boolean active,
        Instant createdAt) {
}
