package com.tpverp.bridge.spi;

import java.util.Map;

public record PairingRequest(
        String provider,
        String terminalId,
        TerminalExecutionMode mode,
        String pairingId,
        String idempotencyKey,
        String configurationReference,
        long configurationVersion,
        Map<String, String> parameters) {
}
