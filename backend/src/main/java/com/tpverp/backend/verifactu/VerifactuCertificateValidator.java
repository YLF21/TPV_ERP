package com.tpverp.backend.verifactu;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class VerifactuCertificateValidator {

    private final Clock clock;

    public VerifactuCertificateValidator(Clock clock) {
        this.clock = clock;
    }

    public VerifactuCertificateStatus validate(X509Certificate certificate) {
        certificate = Objects.requireNonNull(certificate, "certificate");
        var now = clock.instant();
        var notBefore = certificate.getNotBefore().toInstant();
        var notAfter = certificate.getNotAfter().toInstant();
        var warning = warning(now, notBefore, notAfter);
        return new VerifactuCertificateStatus(
                warning == null,
                warning,
                certificate.getSubjectX500Principal().getName(),
                notBefore,
                notAfter);
    }
    // Evalua si el certificado puede usarse y devuelve avisos no bloqueantes para pantalla.

    private static String warning(java.time.Instant now, java.time.Instant notBefore, java.time.Instant notAfter) {
        if (now.isAfter(notAfter)) {
            return "CERTIFICATE_EXPIRED";
        }
        if (now.isBefore(notBefore)) {
            return "CERTIFICATE_NOT_YET_VALID";
        }
        return null;
    }
}
