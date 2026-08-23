package com.tpverp.backend.verifactu;

public record FiscalSandboxStatusView(
        boolean enabled,
        FiscalRuntimeClass runtimeClass,
        FiscalEndpointEnvironment endpointEnvironment,
        FiscalTransportMode transportMode,
        SimulatedAeatOutcome nextOutcome) {
}
