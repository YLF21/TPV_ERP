package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuActivationStatus(
        boolean active,
        String mode,
        Instant effectiveActivationAt,
        Instant firstSubmissionAt) {

    static VerifactuActivationStatus unavailable() {
        return new VerifactuActivationStatus(false, "UNAVAILABLE", null, null);
    }
}
