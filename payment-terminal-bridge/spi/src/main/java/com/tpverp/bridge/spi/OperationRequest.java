package com.tpverp.bridge.spi;

import java.util.Map;

public record OperationRequest(
        String provider,
        String terminalId,
        TerminalExecutionMode mode,
        String operationId,
        String idempotencyKey,
        String command,
        long amountMinor,
        String currency,
        String originalOperationId,
        String reference,
        String configurationReference,
        long configurationVersion,
        Map<String, String> parameters) {
}
