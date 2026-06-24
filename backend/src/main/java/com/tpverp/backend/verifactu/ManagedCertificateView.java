package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.UUID;

public record ManagedCertificateView(
        UUID id,
        ManagedCertificateStatus status,
        String subject,
        String issuer,
        String serialNumber,
        String taxId,
        String fingerprint,
        Instant validFrom,
        Instant validUntil) {

    public static ManagedCertificateView from(ManagedVerifactuCertificate certificate) {
        return new ManagedCertificateView(
                certificate.getId(), certificate.getStatus(), certificate.getSubject(),
                certificate.getIssuer(), certificate.getSerialNumber(), certificate.getTaxId(),
                certificate.getFingerprint(), certificate.getValidFrom(), certificate.getValidUntil());
    }
    // Expone exclusivamente metadatos publicos del certificado.
}
