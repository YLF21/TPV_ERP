package com.tpverp.saas.admin;

import java.time.Instant;

public record PairingCodeResponse(
        String licenseReference,
        String pairingCode,
        Instant expiresAt) {
}
