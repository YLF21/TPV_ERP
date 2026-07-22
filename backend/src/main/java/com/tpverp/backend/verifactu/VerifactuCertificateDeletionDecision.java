package com.tpverp.backend.verifactu;

public record VerifactuCertificateDeletionDecision(
        boolean canDelete,
        String deleteBlockReason) {

    public static VerifactuCertificateDeletionDecision allowed() {
        return new VerifactuCertificateDeletionDecision(true, null);
    }

    public static VerifactuCertificateDeletionDecision blocked(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("deleteBlockReason es obligatorio");
        }
        return new VerifactuCertificateDeletionDecision(false, reason);
    }
}
