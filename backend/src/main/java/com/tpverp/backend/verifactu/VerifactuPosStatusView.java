package com.tpverp.backend.verifactu;

public record VerifactuPosStatusView(
        boolean active,
        VerifactuPosPresentationStatus presentationStatus,
        long pendingCount,
        long sendingCount,
        long reviewRequiredCount) {
}
