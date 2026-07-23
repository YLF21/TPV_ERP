package com.tpverp.backend.verifactu;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        Instant validUntil,
        ManagedCertificateValidityStatus validityStatus,
        long daysRemaining,
        boolean canDelete,
        String deleteBlockReason) {

    public static ManagedCertificateView from(
            ManagedVerifactuCertificate certificate,
            Instant now,
            VerifactuCertificateDeletionDecision deletion) {
        return new ManagedCertificateView(
                certificate.getId(), certificate.getStatus(), certificate.getSubject(),
                certificate.getIssuer(), certificate.getSerialNumber(), certificate.getTaxId(),
                certificate.getFingerprint(), certificate.getValidFrom(), certificate.getValidUntil(),
                validityStatus(certificate, now),
                remainingDays(certificate.getValidUntil(), now),
                deletion.canDelete(), deletion.deleteBlockReason());
    }

    static long remainingDays(Instant validUntil, Instant now) {
        var remaining = Duration.between(now, validUntil);
        var wholeDays = remaining.toDays();
        var remainder = remaining.minusDays(wholeDays);
        if (remainder.isZero()) {
            return wholeDays;
        }
        return wholeDays + (remaining.isNegative() ? -1 : 1);
    }

    private static ManagedCertificateValidityStatus validityStatus(
            ManagedVerifactuCertificate certificate,
            Instant now) {
        if (now.isBefore(certificate.getValidFrom())) {
            return ManagedCertificateValidityStatus.TODAVIA_NO_VALIDO;
        }
        if (now.isAfter(certificate.getValidUntil())) {
            return ManagedCertificateValidityStatus.CADUCADO;
        }
        if (!certificate.getValidUntil().isAfter(now.plus(30, ChronoUnit.DAYS))) {
            return ManagedCertificateValidityStatus.PROXIMO_A_CADUCAR;
        }
        return ManagedCertificateValidityStatus.VALIDO;
    }
    // Expone exclusivamente metadatos publicos del certificado.
}
