package com.tpverp.backend.terminal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PaymentTerminalGatewayContext(UUID terminalId, PaymentTerminalProvider provider,
        PaymentTerminalMode mode, String currency, String idempotencyKey,
        String configurationReference, long configurationVersion, String configurationHash,
        Map<String, String> parameters) {
    public PaymentTerminalGatewayContext {
        Objects.requireNonNull(terminalId); Objects.requireNonNull(provider); Objects.requireNonNull(mode);
        Objects.requireNonNull(currency); Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(configurationReference); Objects.requireNonNull(parameters);
        if (!"EUR".equals(currency)) throw new IllegalArgumentException("Only EUR is supported");
        if (configurationReference.isBlank() || configurationReference.length() > 128
                || configurationReference.toLowerCase(java.util.Locale.ROOT).startsWith("secret:")) {
            throw new IllegalArgumentException("Invalid non-secret configuration reference");
        }
        if (configurationVersion < 0) throw new IllegalArgumentException("configurationVersion must be non-negative");
        if (configurationHash != null && !configurationHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("configurationHash must be a SHA-256 hex value");
        }
        parameters.forEach((key, value) -> {
            Objects.requireNonNull(key); Objects.requireNonNull(value);
            var normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("secret") || normalized.contains("password")
                    || normalized.contains("credential") || normalized.contains("token")
                    || normalized.equals("apikey") || normalized.equals("api_key")) {
                throw new IllegalArgumentException("Secrets are not allowed in gateway parameters");
            }
        });
        parameters=Map.copyOf(parameters);
    }

    /** Compatibility constructor for the existing Redsys POS flow. */
    public PaymentTerminalGatewayContext(UUID terminalId, PaymentTerminalProvider provider,
            PaymentTerminalMode mode, String currency, String idempotencyKey, Map<String, String> parameters) {
        this(terminalId, provider, mode, currency, idempotencyKey,
                provider == PaymentTerminalProvider.REDSYS_TPV_PC ? "legacy:redsys" : "legacy:" + provider.name().toLowerCase(java.util.Locale.ROOT),
                0L, null, parameters);
    }
}
