package com.tpverp.saas.admin;

import java.time.Instant;

public record AdminUserResponse(String username, boolean active, Instant createdAt) {
}
