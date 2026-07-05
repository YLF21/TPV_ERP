package com.tpverp.saas.tenant;

import java.time.Instant;
import java.util.UUID;

public record TenantStoreResponse(UUID storeId, String code, String name, Instant createdAt) {
}
