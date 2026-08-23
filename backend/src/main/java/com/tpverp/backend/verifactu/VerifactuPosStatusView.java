package com.tpverp.backend.verifactu;

public record VerifactuPosStatusView(
        boolean active,
        VerifactuPosPresentationStatus presentationStatus,
        long pendingCount,
        long sendingCount,
        long reviewRequiredCount,
        FiscalMode fiscalMode,
        FiscalRuntimeClass runtimeClass,
        FiscalEndpointEnvironment endpointEnvironment,
        FiscalTransportMode transportMode) {

    /** Compatibility constructor for existing direct callers and contract tests. */
    public VerifactuPosStatusView(
            boolean active,
            VerifactuPosPresentationStatus presentationStatus,
            long pendingCount,
            long sendingCount,
            long reviewRequiredCount) {
        this(active, presentationStatus, pendingCount, sendingCount, reviewRequiredCount,
                active ? FiscalMode.VERIFACTU : FiscalMode.PRE_SIF,
                FiscalRuntimeClass.REAL,
                FiscalEndpointEnvironment.TEST,
                FiscalTransportMode.AEAT);
    }
}
