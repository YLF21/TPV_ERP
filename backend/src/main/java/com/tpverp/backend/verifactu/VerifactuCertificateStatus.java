package com.tpverp.backend.verifactu;

import java.time.Instant;

public record VerifactuCertificateStatus(
        boolean valid,
        String warning,
        String subject,
        Instant notBefore,
        Instant notAfter) {
}
