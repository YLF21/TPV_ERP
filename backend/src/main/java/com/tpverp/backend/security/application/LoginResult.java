package com.tpverp.backend.security.application;

import java.util.Set;
import java.util.UUID;

public record LoginResult(
        String accessToken,
        UUID userId,
        String userName,
        String role,
        boolean mustChangePassword,
        Set<String> permissions) {

    public LoginResult {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
