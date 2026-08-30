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
        String timezone,
        String releaseId,
        String systemVersion,
        FiscalProductCapability productCapability,
        String declarationSha256,
        boolean declarationAvailable,
        Long pendingCount,
        Instant oldestPendingAt,
        Instant lastAeatSuccessAt) {

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
                transportMode, productionEnabled, verifactuBlockedUntil, scheduledTransition,
                null, null, null, null, null, false, null, null, null);
    }

    /** Compatibility constructor for clients compiled before release metadata was exposed. */
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
            FiscalScheduledTransitionView scheduledTransition,
            String timezone) {
        this(companyId, mode, modeVersion, modeSince, runtimeClass, endpointEnvironment,
                transportMode, productionEnabled, verifactuBlockedUntil, scheduledTransition,
                timezone, null, null, null, null, false, null, null, null);
    }
}
