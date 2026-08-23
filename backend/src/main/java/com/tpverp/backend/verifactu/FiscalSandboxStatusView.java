package com.tpverp.backend.verifactu;

public record FiscalSandboxStatusView(
        boolean sandboxEnabled,
        FiscalRuntimeClass runtimeClass,
        FiscalEndpointEnvironment endpointEnvironment,
        FiscalTransportMode transportMode,
        SimulatedAeatOutcome nextOutcome) {
}
