package com.tpverp.backend.terminal.bridge;

import java.util.Map;
import java.util.Set;

public record BridgeOperationRequest(
        String provider,
        String terminalId,
        String mode,
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
    private static final Set<String> COMMANDS = Set.of(
            "PAIRING_STATUS", "CHARGE", "QUERY", "VOID", "REFUND", "RECEIPT", "RECONCILIATION");

    public BridgeOperationRequest {
        optionalIdentifier(provider, "provider", 32);
        optionalIdentifier(terminalId, "terminalId", 64);
        if (!Set.of("SIMULATED", "LIVE").contains(mode)) throw new IllegalArgumentException("mode");
        required(operationId, "operationId", 128);
        optional(idempotencyKey, "idempotencyKey", 128);
        if (command == null || !COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Bridge command is not allowed");
        }
        if (amountMinor < 0 || ((command.equals("CHARGE") || command.equals("REFUND")) && amountMinor == 0)) {
            throw new IllegalArgumentException("amountMinor");
        }
        if (!"EUR".equals(currency)) throw new IllegalArgumentException("Only EUR is allowed");
        optionalIdentifier(originalOperationId, "originalOperationId", 128);
        optional(reference, "reference", 256);
        optional(configurationReference, "configurationReference", 128);
        if (configurationVersion < 0) throw new IllegalArgumentException("configurationVersion");
        parameters = safeParameters(parameters);
    }

    public BridgeOperationRequest(String operationId, String command, long amountMinor, String currency) {
        this(null, null, "LIVE", operationId, operationId, command, amountMinor, currency,
                null, null, null, 0L, Map.of());
    }

    private static Map<String, String> safeParameters(Map<String, String> input) {
        var source = input == null ? Map.<String, String>of() : input;
        if (source.size() > 32) throw new IllegalArgumentException("Too many bridge parameters");
        source.forEach((key, value) -> {
            required(key, "parameter key", 64);
            required(value, "parameter value", 256);
            var lower = key.toLowerCase(java.util.Locale.ROOT);
            if (Set.of("pan", "pin", "cvv", "cvc", "secret", "token", "password", "credential", "apikey", "api_key")
                    .stream().anyMatch(lower::contains)) {
                throw new IllegalArgumentException("Sensitive bridge parameter is not allowed");
            }
        });
        return Map.copyOf(source);
    }

    private static void required(String value, String field, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        optional(value, field, maximum);
    }

    private static void optionalIdentifier(String value, String field, int maximum) {
        if (value == null) return;
        optional(value, field, maximum);
        if (!value.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException(field);
    }

    private static void optional(String value, String field, int maximum) {
        if (value == null) return;
        if (value.length() > maximum || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(field);
        }
    }
}
