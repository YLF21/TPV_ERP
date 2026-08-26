package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FiscalStatusView(
        UUID companyId,
        FiscalMode mode,
        long modeVersion,
        Instant modeSince,
        FiscalRuntimeClass runtimeClass,
        FiscalEndpointEnvironment endpointEnvironment,
        FiscalTransportMode transportMode,
        boolean productionEnabled,
        LocalDate verifactuBlockedUntil,
        FiscalScheduledTransitionView scheduledTransition,
        String timezone) {

    /** Compatibility constructor for clients compiled before the store timezone was exposed. */
    public FiscalStatusView(
            UUID companyId,
            FiscalMode mode,
            long modeVersion,
            Instant modeSince,
            FiscalRuntimeClass runtimeClass,
            FiscalEndpointEnvironment endpointEnvironment,
            FiscalTransportMode transportMode,
            boolean productionEnabled,
            LocalDate verifactuBlockedUntil,
            FiscalScheduledTransitionView scheduledTransition) {
        this(companyId, mode, modeVersion, modeSince, runtimeClass, endpointEnvironment,
                transportMode, productionEnabled, verifactuBlockedUntil, scheduledTransition, null);
    }
}
