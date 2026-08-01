package com.tpverp.saas.admin;

import java.time.Instant;

public record SaasLoginResponse(
        String username,
        String accessToken,
        String mode,
        Instant expiresAt) {
}
