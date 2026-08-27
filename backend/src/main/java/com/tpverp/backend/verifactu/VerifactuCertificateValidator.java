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
        var warning = warning(certificate, now, notBefore, notAfter);
        return new VerifactuCertificateStatus(
                warning == null,
                warning,
                certificate.getSubjectX500Principal().getName(),
                notBefore,
                notAfter);
    }
    // Evalua si el certificado puede usarse y devuelve avisos no bloqueantes para pantalla.

    private static String warning(
            X509Certificate certificate,
            java.time.Instant now,
            java.time.Instant notBefore,
            java.time.Instant notAfter) {
        if (now.isAfter(notAfter)) {
            return "CERTIFICATE_EXPIRED";
        }
        if (now.isBefore(notBefore)) {
            return "CERTIFICATE_NOT_YET_VALID";
        }
        // A signing certificate must be usable for digital signatures. Some
        // providers omit KeyUsage, in which case the issuer policy is the
        // authority and the importer performs the key-pair challenge.
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null
                && (keyUsage.length == 0
                        || (!keyUsage[0] && (keyUsage.length < 2 || !keyUsage[1])))) {
            return "CERTIFICATE_KEY_USAGE_INVALID";
        }
        // A CA certificate must never be selected as the signing leaf.
        if (certificate.getBasicConstraints() >= 0) {
            return "CERTIFICATE_KEY_USAGE_INVALID";
        }
        return null;
    }
}
