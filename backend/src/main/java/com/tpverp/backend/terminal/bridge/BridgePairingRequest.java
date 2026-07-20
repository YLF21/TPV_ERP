package com.tpverp.backend.terminal.bridge;

import java.util.Map;

public record BridgePairingRequest(
        String provider,
        String terminalId,
        String mode,
        String pairingId,
        String idempotencyKey,
        String configurationReference,
        long configurationVersion,
        Map<String, String> parameters) {
    public BridgePairingRequest {
        var validated = new BridgeOperationRequest(provider, terminalId, mode, pairingId, idempotencyKey,
                "PAIRING_STATUS", 0L, "EUR", null, null, configurationReference,
                configurationVersion, parameters);
        parameters = validated.parameters();
    }
}
