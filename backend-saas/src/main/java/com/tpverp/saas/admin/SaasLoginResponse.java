package com.tpverp.saas.admin;

import java.time.Instant;

public record SaasLoginResponse(
        String username,
        String accessToken,
        String mode,
        Instant expiresAt,
        boolean passwordChangeRequired) {

    public SaasLoginResponse(String username, String accessToken, String mode, Instant expiresAt) {
        this(username, accessToken, mode, expiresAt, false);
    }
}
